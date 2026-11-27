package io.github.yuvrajtiwary.earlybird.poller;

import io.github.yuvrajtiwary.earlybird.common.AdminAlerts;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.OutboxRelay;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.TickRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One poller tick: check every board that is due (failing boards back off), record the outcome, tell the admin
 * if ticks keep failing or many boards do, then run the outbox relay, which publishes events that didn't reach
 * the bus earlier.
 * Boards are checked in parallel on virtual threads, at most {@value #MAX_CONCURRENT_BOARDS} at a time so
 * the 5-connection database pool and 512 MB of memory aren't overwhelmed. One board failing, even with an
 * unexpected exception, never stops the others.
 */
@Service
@ConditionalOnRole(Role.POLLER)
public class TickService {

    private static final Logger log = LoggerFactory.getLogger(TickService.class);

    static final int MAX_CONCURRENT_BOARDS = 8;
    /** Spec 3.11: the admin hears about 3 failed ticks in a row, or more than 20% of boards failing. */
    static final int FAILED_TICKS_ALERT = 3;
    static final double FAILING_BOARDS_ALERT = 0.20;

    public record TickSummary(
            long tickId,
            int boardsChecked,
            int boardsFailed,
            int boardsUnchanged,
            int newPostings,
            int closedPostings,
            Duration took,
            TickRepository.Status status) {}

    private final BoardRepository boards;
    private final TickRepository ticks;
    private final BoardChecker checker;
    private final OutboxRelay relay;
    private final AdminAlerts admin;
    private final Clock clock;
    private final MeterRegistry meters;

    public TickService(BoardRepository boards, TickRepository ticks, BoardChecker checker, OutboxRelay relay,
            AdminAlerts admin, Clock clock, MeterRegistry meters) {
        this.boards = boards;
        this.ticks = ticks;
        this.checker = checker;
        this.relay = relay;
        this.admin = admin;
        this.clock = clock;
        this.meters = meters;
    }

    public TickSummary runTick() {
        Instant started = clock.instant();
        Timer.Sample timer = Timer.start(meters);
        long tickId = ticks.start(started);
        List<BoardCheckResult> results = checkAll(boards.findDue(started));

        int failed = (int) results.stream().filter(BoardCheckResult::failed).count();
        int unchanged = (int) results.stream()
                .filter(r -> r.outcome() == BoardCheckResult.Outcome.NOT_MODIFIED).count();
        int newPostings = results.stream().mapToInt(BoardCheckResult::newPostings).sum();
        int closed = results.stream().mapToInt(BoardCheckResult::closedPostings).sum();
        TickRepository.Status status = failed == 0 ? TickRepository.Status.OK
                : failed == results.size() ? TickRepository.Status.FAILED
                : TickRepository.Status.PARTIAL;

        Instant finished = clock.instant();
        ticks.finish(tickId, finished, results.size(), failed, newPostings, closed, status);
        timer.stop(meters.timer("earlybird.tick"));

        var summary = new TickSummary(tickId, results.size(), failed, unchanged, newPostings, closed,
                Duration.between(started, finished), status);
        log.info("Tick {}: {} boards ({} unchanged, {} failed), {} new postings, {} closed, {} ms",
                tickId, summary.boardsChecked(), unchanged, failed, newPostings, closed, summary.took().toMillis());
        alertIfUnhealthy();
        try {
            relay.run();
        } catch (RuntimeException e) {
            log.error("Outbox relay failed; it runs again next tick", e); // the tick itself succeeded
        }
        return summary;
    }

    private void alertIfUnhealthy() {
        List<TickRepository.Status> recent = ticks.latestStatuses(FAILED_TICKS_ALERT);
        if (recent.size() == FAILED_TICKS_ALERT && recent.stream().allMatch(s -> s == TickRepository.Status.FAILED)) {
            admin.notifyOncePerDay("ticks-failing", "The last " + FAILED_TICKS_ALERT
                    + " ticks all failed: no board answered. Is the network or the database down?");
        }
        List<Board> enabled = boards.findEnabled();
        long failing = enabled.stream().filter(b -> b.consecutiveFailures() > 0).count();
        if (!enabled.isEmpty() && failing > FAILING_BOARDS_ALERT * enabled.size()) {
            admin.notifyOncePerDay("boards-failing", failing + " of " + enabled.size()
                    + " boards are failing (over 20%). /admin has details.");
        }
    }

    private List<BoardCheckResult> checkAll(List<Board> enabled) {
        Semaphore slots = new Semaphore(MAX_CONCURRENT_BOARDS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<BoardCheckResult>> futures = enabled.stream()
                    .map(board -> executor.submit(() -> {
                        slots.acquire();
                        try {
                            return checker.check(board);
                        } catch (RuntimeException e) {
                            log.error("Board {} ({} {}) crashed the check", board.id(), board.source(), board.token(), e);
                            return BoardCheckResult.failed(board, e.toString());
                        } finally {
                            slots.release();
                        }
                    }))
                    .toList();
            return futures.stream().map(TickService::join).toList();
        }
    }

    private static BoardCheckResult join(Future<BoardCheckResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tick interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
