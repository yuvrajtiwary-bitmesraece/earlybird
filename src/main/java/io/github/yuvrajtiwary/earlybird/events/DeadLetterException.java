package io.github.yuvrajtiwary.earlybird.events;

/**
 * "Retrying can't help": the event is malformed, or a consumer has decided it can never be processed (for
 * example, Telegram rejected the alert with a 4xx). With Kafka the event goes to the dead-letter topic at once,
 * without the usual retries; the in-process bus only logs it.
 */
public class DeadLetterException extends RuntimeException {

    public DeadLetterException(String message) {
        super(message);
    }

    public DeadLetterException(String message, Throwable cause) {
        super(message, cause);
    }
}
