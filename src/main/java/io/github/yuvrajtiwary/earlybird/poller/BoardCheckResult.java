package io.github.yuvrajtiwary.earlybird.poller;

import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.util.Locale;

/** What happened to one board in one tick. */
public record BoardCheckResult(
        long boardId, Source source, Outcome outcome, int jobs, int newPostings, int closedPostings, String error) {

    public enum Outcome {
        /** Full response, applied. */
        UPDATED,
        /** 304: nothing changed. */
        NOT_MODIFIED,
        /** 0 jobs from a board that had many; ignored this time, counted as a failure. */
        SUSPICIOUS_EMPTY,
        /** Couldn't read the board. */
        FAILED;

        /** Metric tag value. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public boolean failed() {
        return outcome == Outcome.FAILED || outcome == Outcome.SUSPICIOUS_EMPTY;
    }

    static BoardCheckResult updated(Board board, int jobs, int newPostings, int closedPostings) {
        return new BoardCheckResult(board.id(), board.source(), Outcome.UPDATED, jobs, newPostings, closedPostings, null);
    }

    static BoardCheckResult notModified(Board board) {
        return new BoardCheckResult(board.id(), board.source(), Outcome.NOT_MODIFIED, 0, 0, 0, null);
    }

    static BoardCheckResult suspiciousEmpty(Board board, String error) {
        return new BoardCheckResult(board.id(), board.source(), Outcome.SUSPICIOUS_EMPTY, 0, 0, 0, error);
    }

    static BoardCheckResult failed(Board board, String error) {
        return new BoardCheckResult(board.id(), board.source(), Outcome.FAILED, 0, 0, 0, error);
    }
}
