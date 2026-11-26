package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

class DeadLetterRecovererTest {

    private static final Instant NOW = Instant.parse("2026-11-24T08:30:00Z");

    @SuppressWarnings("unchecked")
    private final KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);
    private final EventJson json = new EventJson();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeadLetterRecoverer recoverer = new DeadLetterRecoverer(kafka, json, "eb.dlq.v1", "eb-matcher",
            Clock.fixed(NOW, ZoneOffset.UTC), meters);

    private static ConsumerRecord<String, byte[]> record(String topic, int attempts) {
        var record = new ConsumerRecord<>(topic, 1, 77L, "the-key", "not json".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, ByteBuffer.allocate(4).putInt(attempts).array());
        return record;
    }

    @Test
    void publishesTheSpecDeadLetterKeyedLikeTheOriginal() {
        when(kafka.send(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var failure = new ListenerExecutionFailedException("listener failed", new IllegalStateException("boom"));

        recoverer.accept(record("eb.jobs.enriched.v1", 4), failure);

        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("eb.dlq.v1"), org.mockito.ArgumentMatchers.eq("the-key"),
                value.capture());
        EventEnvelope<DeadLetter> event = json.read(value.getValue(), DeadLetter.class);
        assertThat(event.producer()).isEqualTo("eb-matcher");
        assertThat(event.payload()).isEqualTo(new DeadLetter("eb.jobs.enriched.v1", 1, 77L, "the-key",
                "IllegalStateException: boom", 4, NOW, Base64.getEncoder().encodeToString("not json".getBytes())));
        assertThat(meters.counter("earlybird.events.dead_letters", "topic", "eb.jobs.enriched.v1").count()).isEqualTo(1);
    }

    /** If the dead letter can't be written, the original must not be committed: throwing makes it retry. */
    @Test
    void throwsWhenTheDeadLetterCannotBePublished() {
        when(kafka.send(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> recoverer.accept(record("eb.jobs.enriched.v1", 4), new RuntimeException("x")))
                .hasMessageContaining("Could not dead-letter eb.jobs.enriched.v1-1@77");
    }

    /** A dead letter that can't be handled is only logged: dead-lettering it again would loop forever. */
    @Test
    void neverDeadLettersADeadLetter() {
        recoverer.accept(record("eb.dlq.v1", 4), new RuntimeException("Telegram down"));

        verify(kafka, never()).send(anyString(), anyString(), any(byte[].class));
    }

    @Test
    void readsAttemptsFromTheContainerHeaderAndCutsLongErrors() {
        assertThat(DeadLetterRecoverer.attempts(record("t", 4))).isEqualTo(4);
        assertThat(DeadLetterRecoverer.attempts(new ConsumerRecord<>("t", 0, 0L, "k", "v"))).isEqualTo(1);
        assertThat(DeadLetterRecoverer.describe(new IllegalStateException("x".repeat(2_000))))
                .hasSize(DeadLetterRecoverer.MAX_ERROR_CHARS).startsWith("IllegalStateException: xxx").endsWith("…");
    }
}
