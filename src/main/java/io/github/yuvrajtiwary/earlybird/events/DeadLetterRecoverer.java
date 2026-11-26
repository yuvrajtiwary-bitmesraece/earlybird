package io.github.yuvrajtiwary.earlybird.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * The last step for an event that failed every attempt, or a poison event: publish a {@link DeadLetter}
 * (spec 3.2) to {@code eb.dlq.v1}, keyed like the original, and wait for Kafka to confirm it. Only then may
 * the error handler commit the original's offset. If the dead letter can't be published, this throws: the
 * offset stays uncommitted and the event is tried again later, so nothing is dropped silently.
 *
 * <p>An event that fails on the dead-letter topic itself (the admin notice couldn't be sent) is only logged:
 * dead-lettering a dead letter would loop.
 */
final class DeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);
    static final int MAX_ERROR_CHARS = 500;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaOperations<String, byte[]> kafka;
    private final EventJson json;
    private final String deadLetterTopic;
    private final String consumerGroup;
    private final Clock clock;
    private final MeterRegistry meters;

    DeadLetterRecoverer(KafkaOperations<String, byte[]> kafka, EventJson json, String deadLetterTopic,
            String consumerGroup, Clock clock, MeterRegistry meters) {
        this.kafka = kafka;
        this.json = json;
        this.deadLetterTopic = deadLetterTopic;
        this.consumerGroup = consumerGroup;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        String where = record.topic() + "-" + record.partition() + "@" + record.offset();
        String error = describe(failure);
        int attempts = attempts(record);
        if (record.topic().equals(deadLetterTopic)) {
            log.error("Gave up on dead letter {} after {} attempts in {}: {}", where, attempts, consumerGroup, error);
            return;
        }
        String key = record.key() == null ? null : record.key().toString();
        var letter = new DeadLetter(record.topic(), record.partition(), record.offset(), key, error, attempts,
                clock.instant(), base64(record.value()));
        try {
            kafka.send(deadLetterTopic, key, json.write(EventEnvelope.of(letter, consumerGroup, clock)))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dead-lettering " + where, e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not dead-letter " + where + " (it will be retried): " + e, e);
        }
        meters.counter("earlybird.events.dead_letters", "topic", record.topic()).increment();
        log.error("Dead-lettered {} after {} attempts in {}: {}", where, attempts, consumerGroup, error);
    }

    /** The container's delivery-attempt header: 1 on the first try, 4 after three retries. */
    static int attempts(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        return header == null || header.value() == null || header.value().length != Integer.BYTES
                ? 1 : ByteBuffer.wrap(header.value()).getInt();
    }

    /** The handler's own exception (not the container's wrapper), as "Type: message", at most 500 chars. */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof ListenerExecutionFailedException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String text = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return text.length() <= MAX_ERROR_CHARS ? text : text.substring(0, MAX_ERROR_CHARS - 1) + "…";
    }

    private static String base64(Object value) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value instanceof byte[] b ? b : value.toString().getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
