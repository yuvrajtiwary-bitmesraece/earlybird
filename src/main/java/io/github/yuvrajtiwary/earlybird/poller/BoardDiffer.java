package io.github.yuvrajtiwary.earlybird.poller;

import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.sources.SourceJob;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The poller's decision rules, as pure functions so they can be tested without a database or network
 * (spec section 3.6, plus the baseline rule).
 */
public final class BoardDiffer {

    /** A posting missing from this many successful checks in a row is closed. */
    public static final int CLOSE_AFTER_MISSES = 2;

    /** A board that had at least this many jobs and suddenly lists none is suspicious... */
    static final int SUSPICIOUS_IF_HAD_AT_LEAST = 5;

    /** ...until it has said "0 jobs" this many times in a row. */
    static final int EMPTY_CHECKS_BEFORE_BELIEVED = 3;

    /** Baseline jobs published within this window are still tagged, for "recent matches" and the digest. */
    static final Duration RECENT_BASELINE_WINDOW = Duration.ofHours(48);

    private BoardDiffer() {}

    /** The first successful check of a board finds jobs that were already there: they are the baseline. */
    public static boolean isBaselineCheck(Board board) {
        return board.lastSuccessAt() == null;
    }

    /**
     * A board that had {@value #SUSPICIOUS_IF_HAD_AT_LEAST}+ jobs and now returns 0 is more likely broken
     * (a bad deploy, an auth glitch) than truly empty. Closing every posting would be wrong, so the first
     * {@code EMPTY_CHECKS_BEFORE_BELIEVED - 1} such answers are ignored.
     */
    public static boolean isSuspiciousEmpty(Board board, int jobsNow) {
        return jobsNow == 0
                && board.lastJobCount() != null
                && board.lastJobCount() >= SUSPICIOUS_IF_HAD_AT_LEAST
                && board.consecutiveEmptyChecks() + 1 < EMPTY_CHECKS_BEFORE_BELIEVED;
    }

    /** Drops repeated ids (keeps the first), since a list is keyed by id from here on. */
    public static List<SourceJob> dedupe(List<SourceJob> jobs) {
        Set<String> seen = new HashSet<>();
        List<SourceJob> unique = new ArrayList<>(jobs.size());
        for (SourceJob job : jobs) {
            if (seen.add(job.externalId())) {
                unique.add(job);
            }
        }
        return unique;
    }

    /** Jobs whose id this board has never had. */
    public static List<SourceJob> newJobs(List<SourceJob> current, Set<String> knownIds) {
        return current.stream().filter(j -> !knownIds.contains(j.externalId())).toList();
    }

    /**
     * Whether a posting goes on to tagging and matching (PostingDiscovered). Reposts never do. Baseline
     * postings do only if the source gives a trustworthy publish time within the last 48 hours of when we
     * first saw them. Uses {@code firstSeenAt}, not the current time, so the answer never changes later
     * (the outbox relay relies on that).
     */
    public static boolean shouldPublish(boolean baseline, boolean repost, Instant publishedAt, Instant firstSeenAt) {
        if (repost) {
            return false;
        }
        if (!baseline) {
            return true;
        }
        return publishedAt != null && !publishedAt.isBefore(firstSeenAt.minus(RECENT_BASELINE_WINDOW));
    }
}
