package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.events.EventEnvelope;
import io.github.yuvrajtiwary.earlybird.events.PostingDiscovered;
import io.github.yuvrajtiwary.earlybird.events.PostingEnriched;
import io.github.yuvrajtiwary.earlybird.events.Topic;
import io.github.yuvrajtiwary.earlybird.ops.AdminNotifier;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTagsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The enricher (spec 3.7): tags new postings in batches of up to 15, or whatever has arrived after 10 seconds.
 *
 * <p>The postings table is the work queue: a posting needs tagging while it still has a description. A
 * PostingDiscovered event is only a doorbell that starts a batch early, and a sweep every 30 s finds anything
 * whose event never arrived (a restart, a lost message, the backlog from before this existed). So no event
 * can lose work, and tagging twice is impossible: tagging clears the description.
 *
 * <p>Per batch: Gemini; postings it didn't answer for are retried once in a half-size batch; whatever is
 * still missing, or everything if Gemini is down or the budget is spent, goes to the keyword tagger. Tags
 * and the cleared description are written in one transaction, then PostingEnriched is published.
 */
public class TaggingWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TaggingWorker.class);

    static final int BATCH = JobTagger.MAX_BATCH;
    static final Duration BATCH_WINDOW = Duration.ofSeconds(10);
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);
    static final Duration LEASE = Duration.ofMinutes(5);
    /** Older untagged postings aren't worth a Gemini call; retention clears them. */
    static final Duration MAX_AGE = Duration.ofDays(7);

    private final PostingRepository postings;
    private final PostingTagsRepository tags;
    private final TransactionTemplate transaction;
    private final JobTagger gemini;
    private final FallbackTagger fallback;
    private final EventBus bus;
    private final HiringRanking hiring;
    private final AdminNotifier admin;
    private final Clock clock;
    private final MeterRegistry meters;
    private final boolean autoStart;
    private final Semaphore doorbell = new Semaphore(0);
    private volatile boolean running;
    private Thread thread;

    public TaggingWorker(PostingRepository postings, PostingTagsRepository tags, TransactionTemplate transaction,
            JobTagger gemini, FallbackTagger fallback, EventBus bus, HiringRanking hiring, AdminNotifier admin,
            Clock clock, MeterRegistry meters, boolean autoStart) {
        this.postings = postings;
        this.tags = tags;
        this.transaction = transaction;
        this.gemini = gemini;
        this.fallback = fallback;
        this.bus = bus;
        this.hiring = hiring;
        this.admin = admin;
        this.clock = clock;
        this.meters = meters;
        this.autoStart = autoStart;
    }

    /** Integration tests turn the background thread off and call {@link #drain()} themselves. */
    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    @Override
    public void start() {
        bus.subscribe(Topic.JOBS_DISCOVERED, "eb-enricher", PostingDiscovered.class, event -> doorbell.release());
        running = true;
        thread = Thread.ofVirtual().name("tagging-worker").start(this::loop);
    }

    private void loop() {
        while (running) {
            try {
                drain();
                // Wait for a doorbell (or the next sweep); then give the batch up to 10 s to fill.
                if (doorbell.tryAcquire(SWEEP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
                    long deadline = System.nanoTime() + BATCH_WINDOW.toNanos();
                    int rung = 1;
                    while (rung < BATCH && doorbell.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        rung++;
                    }
                    doorbell.drainPermits();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Tagging sweep failed", e);
                sleepQuietly(SWEEP_INTERVAL);
            }
        }
    }

    /** Tags batches until nothing is waiting. Public so tests can run it without the background thread. */
    public int drain() {
        int total = 0;
        while (true) {
            Instant now = clock.instant();
            List<Posting> batch = postings.claimUntagged(BATCH, now, LEASE, now.minus(MAX_AGE));
            if (batch.isEmpty()) {
                break;
            }
            tagBatch(batch);
            total += batch.size();
            if (batch.size() < BATCH) {
                break;
            }
        }
        return total;
    }

    void tagBatch(List<Posting> batch) {
        Map<UUID, PostingTags> result = new HashMap<>();
        try {
            result.putAll(gemini.tag(batch));
            List<Posting> missing = batch.stream().filter(p -> !result.containsKey(p.id())).toList();
            if (!missing.isEmpty()) {
                List<Posting> retry = missing.subList(0, Math.min(missing.size(), Math.max(1, BATCH / 2)));
                result.putAll(gemini.tag(retry));
            }
        } catch (GeminiException e) {
            if (e.kind() == GeminiException.Kind.BUDGET_EXHAUSTED) {
                admin.notifyOncePerDay("gemini-budget",
                        "Gemini's daily budget is used up; tagging uses the keyword fallback until it resets.");
            } else {
                log.warn("Gemini tagging failed ({}), using the fallback for {} postings", e.kind(), batch.size() - result.size());
            }
        }
        Instant now = clock.instant();
        for (Posting p : batch) {
            PostingTags t = result.containsKey(p.id()) ? result.get(p.id()) : fallback.tag(p, now);
            transaction.executeWithoutResult(status -> {
                tags.upsert(t);
                postings.finishTagging(p.id());
            });
            meters.counter("earlybird.postings.tagged", "tagger", t.tagger().name().toLowerCase(Locale.ROOT)).increment();
            if (t.fresherFriendly()) {
                hiring.recordFresherPosting(p.company());
            }
            publish(t);
        }
        log.info("Tagged {} postings ({} by Gemini)", batch.size(), result.size());
    }

    private void publish(PostingTags t) {
        var payload = new PostingEnriched(t.postingId(), db(t.roleCategory()), db(t.seniority()), t.minYears(),
                t.maxYears(), t.fresherFriendly(), db(t.employmentType()), db(t.workMode()), db(t.remoteScope()),
                t.cities(), t.countries(), t.skills(), db(t.tagger()));
        try {
            bus.publish(Topic.JOBS_ENRICHED, t.postingId().toString(), EventEnvelope.of(payload, "enricher", clock))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Could not publish PostingEnriched for {}: {}", t.postingId(), e.toString());
        }
    }

    private static String db(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Postings waiting for tags right now, for /admin. */
    public int backlog() {
        Instant now = clock.instant();
        return postings.countUntagged(now.minus(MAX_AGE));
    }
}
