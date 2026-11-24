package io.github.yuvrajtiwary.earlybird.sources;

import java.time.Duration;

/**
 * A board couldn't be read. Unchecked so it can cross the HTTP client's callbacks; the poller catches it
 * per board, so one broken board never stops a tick.
 */
public class SourceException extends RuntimeException {

    public enum Kind {
        /** 404: the board token is wrong or the company left the platform. */
        NOT_FOUND(false),
        /** 429: the platform asked us to slow down; see {@link #retryAfter()}. */
        RATE_LIMITED(false),
        /** 5xx: the platform's problem; worth retrying. */
        SERVER_ERROR(true),
        /** Timeout, connection reset, DNS failure; worth retrying. */
        NETWORK(true),
        /** Any other 4xx. */
        HTTP_ERROR(false),
        /** 200 but not the JSON we expected, or too large. */
        BAD_RESPONSE(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }
    }

    private final Kind kind;
    private final int status;
    private final Duration retryAfter;

    public SourceException(Kind kind, int status, Duration retryAfter, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public SourceException(Kind kind, int status, String message) {
        this(kind, status, null, message, null);
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status, or 0 if there was no response. */
    public int status() {
        return status;
    }

    /** From a 429's Retry-After header, if it had one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean retryable() {
        return kind.retryable;
    }
}
