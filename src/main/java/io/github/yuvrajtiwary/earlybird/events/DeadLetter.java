package io.github.yuvrajtiwary.earlybird.events;

import java.time.Instant;

/**
 * An event a consumer gave up on (topic {@code eb.dlq.v1}, key = the original key). Spec section 3.2. The
 * envelope's {@code producer} is the consumer group that gave up, e.g. {@code eb-matcher}.
 *
 * @param error    the exception's type and message, cut to 500 characters
 * @param attempts how many times the consumer tried: 1 for a poison event, 4 after the 1 s, 5 s, 30 s retries
 * @param payload  the original message, base64-encoded exactly as it was on the topic, so it can be inspected
 *                 or replayed
 */
public record DeadLetter(
        String originalTopic,
        int partition,
        long offset,
        String key,
        String error,
        int attempts,
        Instant failedAt,
        String payload) {}
