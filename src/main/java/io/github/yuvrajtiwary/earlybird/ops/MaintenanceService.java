package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.MaintenanceRepository;
import io.github.yuvrajtiwary.earlybird.sources.BoardValidator;
import io.github.yuvrajtiwary.earlybird.sources.JobSources;
import io.github.yuvrajtiwary.earlybird.sources.SeedBoards.SeedBoard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The nightly job (03:30 IST, spec 3.3 and 3.4): retention cleanup, then a second chance for disabled boards.
 * Boards get disabled by failing 20 checks in a row (see BoardRepository); one that answers with jobs again is
 * re-enabled. Boards that stay dead stay off.
 */
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    static final Duration CLOSED_POSTINGS = Duration.ofDays(60);
    static final Duration ALERTS = Duration.ofDays(180);
    static final Duration PROCESSED_EVENTS = Duration.ofDays(7);
    static final Duration TELEGRAM_UPDATES = Duration.ofDays(2);
    static final Duration TICKS = Duration.ofDays(30);
    /** Same as the tagging worker's limit: older untagged postings are never tagged. */
    static final Duration UNTAGGED_DESCRIPTIONS = Duration.ofDays(7);

    public record Result(int closedPostings, int staleDescriptions, int alerts, int processedEvents,
            int telegramUpdates, int ticks, int boardsReEnabled) {}

    private final MaintenanceRepository repository;
    private final BoardRepository boards;
    private final ObjectProvider<JobSources> sources;
    private final Clock clock;

    public MaintenanceService(MaintenanceRepository repository, BoardRepository boards,
            ObjectProvider<JobSources> sources, Clock clock) {
        this.repository = repository;
        this.boards = boards;
        this.sources = sources;
        this.clock = clock;
    }

    public Result run() {
        Instant now = clock.instant();
        var result = new Result(
                repository.deleteClosedPostings(now.minus(CLOSED_POSTINGS)),
                repository.clearStaleDescriptions(now.minus(UNTAGGED_DESCRIPTIONS)),
                repository.deleteAlerts(now.minus(ALERTS)),
                repository.deleteProcessedEvents(now.minus(PROCESSED_EVENTS)),
                repository.deleteTelegramUpdates(now.minus(TELEGRAM_UPDATES)),
                repository.deleteTicks(now.minus(TICKS)),
                reEnableBoardsThatAnswer(now));
        log.info("Maintenance: {}", result);
        return result;
    }

    private int reEnableBoardsThatAnswer(Instant now) {
        JobSources jobSources = sources.getIfAvailable();
        List<Board> disabled = boards.findDisabled();
        if (jobSources == null || disabled.isEmpty()) {
            return 0;
        }
        var reports = new BoardValidator(jobSources).validate(
                disabled.stream().map(b -> new SeedBoard(b.source(), b.token(), b.company())).toList());
        int reEnabled = 0;
        for (int i = 0; i < disabled.size(); i++) {
            if (reports.get(i).status() == BoardValidator.Status.OK) {
                boards.reEnable(disabled.get(i).id(), now);
                reEnabled++;
            }
        }
        return reEnabled;
    }
}
