package io.github.yuvrajtiwary.earlybird.sources;

import io.github.yuvrajtiwary.earlybird.sources.JobSource.FetchResult;
import io.github.yuvrajtiwary.earlybird.sources.SeedBoards.SeedBoard;
import io.github.yuvrajtiwary.earlybird.sources.SourceException.Kind;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Calls every seed board once and reports whether it works. Requests run on virtual threads, still limited
 * to 4 at a time per host by {@link SourceHttp}.
 */
public final class BoardValidator {

    public enum Status {
        /** Works and lists jobs. */
        OK,
        /** Works, but lists no jobs right now. The token is valid. */
        EMPTY,
        /** 404: wrong token, or the company left the platform. Drop it. */
        NOT_FOUND,
        /** Anything else (timeout, 5xx, bad JSON). Try again before dropping. */
        ERROR
    }

    public record Report(SeedBoard board, Status status, int jobs, int indiaOrRemoteJobs, long millis, String message) {
        public boolean validToken() {
            return status == Status.OK || status == Status.EMPTY;
        }
    }

    private static final Pattern INDIA_OR_REMOTE = Pattern.compile(
            "\\b(india|bengaluru|bangalore|hyderabad|pune|chennai|mumbai|delhi|gurgaon|gurugram|noida|kolkata"
                    + "|ahmedabad|remote|anywhere|worldwide)\\b|\\bIN\\b",
            Pattern.CASE_INSENSITIVE);

    private final JobSources sources;

    public BoardValidator(JobSources sources) {
        this.sources = sources;
    }

    public List<Report> validate(List<SeedBoard> boards) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Report>> futures = boards.stream().map(b -> executor.submit(() -> check(b))).toList();
            return futures.stream().map(BoardValidator::join).toList();
        }
    }

    Report check(SeedBoard board) {
        long start = System.nanoTime();
        try {
            FetchResult result = sources.get(board.source()).fetch(board.token(), null, false);
            long millis = (System.nanoTime() - start) / 1_000_000;
            List<SourceJob> jobs = result instanceof FetchResult.Jobs j ? j.jobs() : List.of();
            int relevant = (int) jobs.stream()
                    .filter(j -> j.locationRaw() != null && INDIA_OR_REMOTE.matcher(j.locationRaw()).find())
                    .count();
            return new Report(board, jobs.isEmpty() ? Status.EMPTY : Status.OK, jobs.size(), relevant, millis, "");
        } catch (SourceException e) {
            long millis = (System.nanoTime() - start) / 1_000_000;
            Status status = e.kind() == Kind.NOT_FOUND ? Status.NOT_FOUND : Status.ERROR;
            return new Report(board, status, 0, 0, millis, e.getMessage());
        }
    }

    private static Report join(Future<Report> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
