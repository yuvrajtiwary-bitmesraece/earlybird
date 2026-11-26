package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTagsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Outbox-lite (spec 3.2). Producers write the database first and mark a row published only after the bus
 * accepted its event, so a crash or a Kafka outage leaves rows unpublished, never lost. At the end of every
 * tick the relay publishes them again:
 * <ul>
 *   <li>postings first seen over a minute ago, still waiting to be tagged, with no PostingDiscovered published;
 *   <li>tags written over a minute ago with no PostingEnriched published. The spec doesn't list this one, but
 *       without it a posting tagged during an outage would never reach the matcher;
 *   <li>instant alerts still queued 2 minutes after they were created. These aren't marked: the notifier moves
 *       the alert on, and ignores a request for an alert that isn't queued any more.
 * </ul>
 * Publishing twice is harmless (consumers de-duplicate); never publishing is not. The relay stops at the first
 * failure: Kafka is probably down, and every further try would only wait for a timeout.
 */
@Service
@ConditionalOnRole(Role.POLLER)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Younger rows may still be on their way: their producer publishes right after writing them. */
    static final Duration POSTING_GRACE = Duration.ofMinutes(1);
    static final Duration ALERT_GRACE = Duration.ofMinutes(2);
    /** Untagged postings this old are never tagged (TaggingWorker.MAX_AGE), and a day-old alert isn't news. */
    static final Duration POSTING_MAX_AGE = Duration.ofDays(7);
    static final Duration ALERT_MAX_AGE = Duration.ofHours(24);
    /** Per kind and run; a long outage's backlog drains over a few ticks. */
    static final int BATCH = 100;
    private static final long PUBLISH_TIMEOUT_SECONDS = 15;
    private static final String PRODUCER = "relay";

    public record Result(int discovered, int enriched, int alerts, boolean stoppedEarly) {

        public int total() {
            return discovered + enriched + alerts;
        }
    }

    private record Sent(List<UUID> accepted, String failure) {}

    private final EventBus bus;
    private final PostingRepository postings;
    private final PostingTagsRepository tags;
    private final AlertRepository alerts;
    private final Clock clock;

    public OutboxRelay(EventBus bus, PostingRepository postings, PostingTagsRepository tags, AlertRepository alerts,
            Clock clock) {
        this.bus = bus;
        this.postings = postings;
        this.tags = tags;
        this.alerts = alerts;
        this.clock = clock;
    }

    public Result run() {
        Instant now = clock.instant();

        Map<UUID, CompletableFuture<Void>> sends = new LinkedHashMap<>();
        for (var u : postings.findUnpublished(now.minus(POSTING_GRACE), now.minus(POSTING_MAX_AGE), BATCH)) {
            sends.put(u.posting().id(), bus.publish(Topic.JOBS_DISCOVERED, u.posting().id().toString(),
                    EventEnvelope.of(PostingDiscovered.of(u.posting(), u.boardToken()), PRODUCER, clock)));
        }
        Sent discovered = awaitAll(sends);
        postings.markPublished(discovered.accepted(), clock.instant());
        if (discovered.failure() != null) {
            return stopped(discovered.failure(), discovered.accepted().size(), 0, 0);
        }

        sends = new LinkedHashMap<>();
        for (PostingTags t : tags.findUnpublished(now.minus(POSTING_GRACE), now.minus(POSTING_MAX_AGE), BATCH)) {
            sends.put(t.postingId(), bus.publish(Topic.JOBS_ENRICHED, t.postingId().toString(),
                    EventEnvelope.of(PostingEnriched.of(t), PRODUCER, clock)));
        }
        Sent enriched = awaitAll(sends);
        tags.markPublished(enriched.accepted(), clock.instant());
        if (enriched.failure() != null) {
            return stopped(enriched.failure(), discovered.accepted().size(), enriched.accepted().size(), 0);
        }

        sends = new LinkedHashMap<>();
        for (Alert a : alerts.findQueuedInstant(now.minus(ALERT_GRACE), now.minus(ALERT_MAX_AGE), BATCH)) {
            // "recent" isn't stored, so a re-requested recent match is labelled as a new one; a rare cosmetic loss.
            sends.put(a.id(), bus.publish(Topic.ALERTS_REQUESTED, String.valueOf(a.userId()),
                    EventEnvelope.of(AlertRequested.of(a, false), PRODUCER, clock)));
        }
        Sent requested = awaitAll(sends);
        if (requested.failure() != null) {
            return stopped(requested.failure(), discovered.accepted().size(), enriched.accepted().size(),
                    requested.accepted().size());
        }

        var result = new Result(discovered.accepted().size(), enriched.accepted().size(),
                requested.accepted().size(), false);
        if (result.total() > 0) {
            log.info("Outbox relay republished {} PostingDiscovered, {} PostingEnriched, {} AlertRequested",
                    result.discovered(), result.enriched(), result.alerts());
        }
        return result;
    }

    private Result stopped(String failure, int discovered, int enriched, int alerts) {
        log.warn("Outbox relay stopped after {} events: {}", discovered + enriched + alerts, failure);
        return new Result(discovered, enriched, alerts, true);
    }

    /** Waits for every send; returns those the bus accepted, and the first failure if any. */
    private static Sent awaitAll(Map<UUID, CompletableFuture<Void>> sends) {
        List<UUID> accepted = new ArrayList<>();
        String failure = null;
        for (var send : sends.entrySet()) {
            try {
                send.getValue().get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                accepted.add(send.getKey());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Sent(accepted, "interrupted");
            } catch (Exception e) {
                if (failure == null) {
                    failure = e.getCause() != null ? e.getCause().toString() : e.toString();
                }
            }
        }
        return new Sent(accepted, failure);
    }
}
