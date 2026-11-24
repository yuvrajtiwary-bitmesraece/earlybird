package io.github.yuvrajtiwary.earlybird.enrich;

/** A Gemini call didn't produce usable output. Callers fall back (job tagging) or ask the user to retry (resumes). */
public class GeminiException extends RuntimeException {

    public enum Kind {
        /** Our daily cap is used up; no request was sent. */
        BUDGET_EXHAUSTED,
        /** 429 / 5xx / timeouts, still failing after retries. */
        UNAVAILABLE,
        /** Other 4xx: our request is wrong (bad key, bad schema). Retrying won't help. */
        REJECTED,
        /** 200, but blocked, cut off, or not the JSON we asked for. */
        BAD_OUTPUT
    }

    private final Kind kind;

    public GeminiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public GeminiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
