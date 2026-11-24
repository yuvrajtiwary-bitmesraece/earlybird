package io.github.yuvrajtiwary.earlybird.telegram.api;

import java.time.Duration;

/**
 * A Bot API call failed. The message never contains the bot token: Telegram puts the token in the URL,
 * so exceptions from the HTTP layer are summarized rather than wrapped.
 *
 * @see <a href="https://core.telegram.org/bots/api#making-requests">Bot API errors</a>
 */
public class TelegramException extends RuntimeException {

    private final int errorCode;
    private final Duration retryAfter;

    public TelegramException(int errorCode, String description, Duration retryAfter) {
        super(errorCode == 0 ? description : errorCode + " " + description, null, false, true);
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    /** Telegram's error_code (400, 403, 409, 429...), or 0 for network errors. */
    public int errorCode() {
        return errorCode;
    }

    /** From a 429's parameters.retry_after. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** 403: the user blocked the bot (or deleted their account). */
    public boolean blockedByUser() {
        return errorCode == 403;
    }
}
