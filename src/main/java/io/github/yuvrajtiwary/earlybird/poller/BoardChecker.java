package io.github.yuvrajtiwary.earlybird.poller;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.events.EventEnvelope;
import io.github.yuvrajtiwary.earlybird.events.PostingClosed;
import io.github.yuvrajtiwary.earlybird.events.PostingDiscovered;
import io.github.yuvrajtiwary.earlybird.events.Topic;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.sources.ContentHash;
import io.github.yuvrajtiwary.earlybird.sources.JobSource;
import io.github.yuvrajtiwary.earlybird.sources.JobSource.FetchResult;
import io.github.yuvrajtiwary.earlybird.sources.JobSources;
import io.github.yuvrajtiwary.earlybird.sources.SourceException;
import io.github.yuvrajtiwary.earlybird.sources.SourceJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks one board: fetch, diff against the database, write, then publish events (spec section 3.6).
 *
 * <p>Order matters. Database writes happen in one transaction; events are published only after it commits,
 * and {@code event_published_at} is set only after the bus accepted each event. If the process dies in
 * between, the posting is in the database with {@code event_published_at = null}, and the outbox relay
 * (Block 5) publishes it later. A posting is never announced before it exists, and never lost.
 */
@Service
@ConditionalOnRole(Role.POLLER)
public class BoardChecker {

    private static final Logger log = LoggerFactory.getLogger(BoardChecker.class);

    static final Duration REPOST_WINDOW = Duration.ofDays(30);
    private static final long PUBLISH_TIMEOUT_SECONDS = 15;
    private static final String PRODUCER = "poller";

    private final JobSources sources;
    private final BoardRepository boards;
    private final PostingRepository postings;
    private final EventBus bus;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meters;

    public BoardChecker(JobSources sources, BoardRepository boards, PostingRepository postings, EventBus bus,
            TransactionTemplate transaction, Clock clock, MeterRegistry meters) {
        this.sources = sources;
        this.boards = boards;
        this.postings = postings;
        this.bus = bus;
        this.transaction = transaction;
        this.clock = clock;
        this.meters = meters;
    }

    public BoardCheckResult check(Board board) {
        BoardCheckResult result = doCheck(board);
        meters.counter("earlybird.boards.checked",
                "source", board.source().name().toLowerCase(Locale.ROOT), "result", result.outcome().tag()).increment();
        return result;
    }

    private BoardCheckResult doCheck(Board board) {
        Instant now = clock.instant();
        boolean baseline = BoardDiffer.isBaselineCheck(board);
        JobSource source = sources.get(board.source());

        // 1. Fetch. The baseline check asks for descriptions up front (one call), because every job is new.
        FetchResult fetched;
        try {
            fetched = source.fetch(board.token(), baseline ? null : board.etag(), baseline);
        } catch (SourceException e) {
            boards.recordFailure(board.id(), now, e.getMessage());
            return BoardCheckResult.failed(board, e.getMessage());
        }
        if (fetched instanceof FetchResult.NotModified) {
            boards.recordNotModified(board.id(), now);
            return BoardCheckResult.notModified(board);
        }
        var listed = (FetchResult.Jobs) fetched;
        List<SourceJob> jobs = BoardDiffer.dedupe(listed.jobs());

        // 2. Don't trust a sudden "0 jobs".
        if (BoardDiffer.isSuspiciousEmpty(board, jobs.size())) {
            String error = "listed 0 jobs after " + board.lastJobCount() + "; ignored ("
                    + (board.consecutiveEmptyChecks() + 1) + " of " + BoardDiffer.EMPTY_CHECKS_BEFORE_BELIEVED + ")";
            boards.recordSuspiciousEmpty(board.id(), now, error);
            return BoardCheckResult.suspiciousEmpty(board, error);
        }

        // 3. Which jobs are new? Fill in descriptions only for those (Greenhouse lists don't carry them).
        List<String> currentIds = jobs.stream().map(SourceJob::externalId).toList();
        Set<String> knownIds = postings.findExternalIds(board.id(), currentIds);
        List<SourceJob> fresh = BoardDiffer.newJobs(jobs, knownIds);
        if (!baseline && fresh.stream().anyMatch(j -> j.description() == null)) {
            try {
                fresh = source.describe(board.token(), fresh);
            } catch (SourceException e) {
                // Nothing written yet, so next tick sees these jobs as new again and retries.
                boards.recordFailure(board.id(), now, "describing new jobs: " + e.getMessage());
                return BoardCheckResult.failed(board, e.getMessage());
            }
        }
        List<Posting> rows = toPostings(board, fresh, baseline, now);

        // 4. One transaction: insert new, refresh seen, count misses / close, update the board.
        record Applied(List<Posting> inserted, List<UUID> closed) {}
        Applied applied = transaction.execute(status -> {
            List<Posting> inserted = postings.insertAll(rows);
            postings.markSeen(board.id(), knownIds, now);
            List<UUID> closed = postings.markMissing(board.id(), currentIds, now, BoardDiffer.CLOSE_AFTER_MISSES);
            boards.recordSuccess(board.id(), now, jobs.size(), listed.etag());
            return new Applied(inserted, closed);
        });

        // 5. After commit: announce.
        publishDiscovered(board, applied.inserted());
        publishClosed(applied.closed(), now);

        long discovered = applied.inserted().stream().filter(p -> !p.baseline()).count();
        if (discovered > 0) {
            meters.counter("earlybird.postings.discovered", "source", board.source().name().toLowerCase(Locale.ROOT))
                    .increment(discovered);
        }
        if (baseline) {
            log.info("Baseline for board {} ({} {}): {} existing jobs stored", board.id(), board.source(),
                    board.token(), applied.inserted().size());
        }
        return BoardCheckResult.updated(board, jobs.size(), applied.inserted().size(), applied.closed().size());
    }

    private List<Posting> toPostings(Board board, List<SourceJob> fresh, boolean baseline, Instant now) {
        Map<SourceJob, String> hashes = new LinkedHashMap<>();
        for (SourceJob job : fresh) {
            hashes.put(job, ContentHash.of(board.company(), job.title(), job.locationRaw(), job.description()));
        }
        Set<String> seenRecently = postings.findHashesSeenSince(new HashSet<>(hashes.values()), now.minus(REPOST_WINDOW));
        Set<String> inThisBatch = new HashSet<>();
        List<Posting> rows = new ArrayList<>(fresh.size());
        hashes.forEach((job, hash) -> {
            // Same fingerprint seen before under another id (or twice in this response) = a repost.
            boolean repost = seenRecently.contains(hash) || !inThisBatch.add(hash);
            boolean publish = BoardDiffer.shouldPublish(baseline, repost, job.publishedAt(), now);
            rows.add(new Posting(Ids.newId(), board.id(), board.source(), job.externalId(), job.url(), job.title(),
                    board.company(), job.locationRaw(), job.departments(), job.publishedAt(), now, now, 0, null, hash,
                    publish ? job.description() : null, // descriptions are kept only until tagged, and only if needed
                    repost, null, baseline));
        });
        return rows;
    }

    private void publishDiscovered(Board board, List<Posting> inserted) {
        Map<UUID, CompletableFuture<Void>> sends = new LinkedHashMap<>();
        for (Posting p : inserted) {
            if (!BoardDiffer.shouldPublish(p.baseline(), p.repost(), p.sourcePublishedAt(), p.firstSeenAt())) {
                continue;
            }
            var payload = new PostingDiscovered(p.id(), p.source().name().toLowerCase(Locale.ROOT), board.token(),
                    p.company(), p.externalId(), p.title(), p.url(), p.locationRaw(), p.departments(),
                    p.sourcePublishedAt(), p.firstSeenAt(), p.contentHash(), p.descriptionText(), p.baseline());
            sends.put(p.id(), bus.publish(Topic.JOBS_DISCOVERED, p.id().toString(),
                    EventEnvelope.of(payload, PRODUCER, clock)));
        }
        List<UUID> accepted = new ArrayList<>();
        sends.forEach((id, send) -> {
            if (await(send, "PostingDiscovered " + id)) {
                accepted.add(id);
            }
        });
        postings.markPublished(accepted, clock.instant());
    }

    private void publishClosed(List<UUID> closed, Instant closedAt) {
        for (UUID id : closed) {
            await(bus.publish(Topic.JOBS_CLOSED, id.toString(),
                    EventEnvelope.of(new PostingClosed(id, closedAt), PRODUCER, clock)), "PostingClosed " + id);
        }
    }

    private static boolean await(CompletableFuture<Void> send, String what) {
        try {
            send.get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Could not publish {} (will be retried by the outbox relay): {}", what, e.toString());
            return false;
        }
    }
}
