package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yuvrajtiwary.earlybird.common.AdminAlerts;
import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** The parts of the Kafka bus that need no broker; KafkaPipelineIT covers the rest against a real one. */
class KafkaEventBusTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final ConsumerFactory<String, byte[]> consumers = mock(ConsumerFactory.class);
    private final KafkaMonitorTest.FakeCluster cluster = new KafkaMonitorTest.FakeCluster();
    private final KafkaMonitor monitor = new KafkaMonitor(cluster, "eb.", mock(AdminAlerts.class), Clock.systemUTC());
    private final EventJson json = new EventJson();

    private KafkaEventBus bus(String env) {
        return new KafkaEventBus(template, consumers, json, monitor, "eb.", env, List.of(), Clock.systemUTC(),
                new SimpleMeterRegistry());
    }

    private static EventEnvelope<PostingClosed> closed() {
        return EventEnvelope.of(new PostingClosed(Ids.newId(), Instant.now()), "poller", Clock.systemUTC());
    }

    @Test
    void publishesTheJsonEnvelopeKeyedOnTheFullTopicName() {
        when(template.send(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var event = closed();

        assertThat(bus("dev").publish(Topic.JOBS_CLOSED, "key-1", event)).isCompleted();

        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(template).send(eq("eb.jobs.closed.v1"), eq("key-1"), value.capture());
        assertThat(json.read(value.getValue(), PostingClosed.class)).isEqualTo(event);
    }

    /** While Kafka is known to be down, fail at once: waiting 10 s per event would stall every tick. */
    @Test
    void failsAtOnceWhileKafkaIsUnreachable() {
        cluster.down = true;
        monitor.probe();

        CompletableFuture<Void> sent = bus("dev").publish(Topic.JOBS_CLOSED, "key-1", closed());

        assertThat(sent).isCompletedExceptionally();
        verify(template, never()).send(anyString(), anyString(), any(byte[].class));
    }

    /** KafkaTemplate throws instead of failing the future when metadata never arrives; callers expect a future. */
    @Test
    void aThrowingSendBecomesAFailedFuture() {
        when(template.send(anyString(), anyString(), any(byte[].class))).thenThrow(new TimeoutException("metadata"));

        assertThat(bus("dev").publish(Topic.JOBS_CLOSED, "key-1", closed())).isCompletedExceptionally();
    }

    /** Dev and prod share Aiven's one free service; a laptop must never join production's groups. */
    @Test
    void groupIdsAreTheSpecsInProductionAndPrefixedElsewhere() {
        assertThat(bus("prod").groupId("eb-matcher")).isEqualTo("eb-matcher");
        assertThat(bus("dev").groupId("eb-matcher")).isEqualTo("dev.eb-matcher");
    }

    @Test
    void aGroupSubscribesToATopicOnce() {
        KafkaEventBus bus = bus("dev");
        bus.subscribe(Topic.JOBS_CLOSED, "eb-matcher", PostingClosed.class, e -> { });

        assertThatThrownBy(() -> bus.subscribe(Topic.JOBS_CLOSED, "eb-matcher", PostingClosed.class, e -> { }))
                .isInstanceOf(IllegalStateException.class);
    }
}
