package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.yuvrajtiwary.earlybird.common.AdminAlerts;
import io.github.yuvrajtiwary.earlybird.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class KafkaMonitorTest {

    private static final List<String> ALL = List.of("eb.jobs.discovered.v1", "eb.jobs.enriched.v1",
            "eb.jobs.closed.v1", "eb.alerts.requested.v1", "eb.dlq.v1");

    /** A cluster the test controls: which topics exist, whether it answers, how many dead letters it holds. */
    static final class FakeCluster implements KafkaMonitor.Cluster {
        Map<String, Integer> topics = new HashMap<>();
        boolean down;
        long deadLetters;

        FakeCluster() {
            ALL.forEach(t -> topics.put(t, 2));
        }

        @Override
        public Map<String, Integer> partitions(Collection<String> wanted, Duration timeout) throws Exception {
            if (down) {
                throw new TimeoutException("Timed out waiting for a node assignment");
            }
            Map<String, Integer> found = new HashMap<>(topics);
            found.keySet().retainAll(wanted);
            return found;
        }

        @Override
        public long retained(String topic, Duration timeout) {
            return deadLetters;
        }
    }

    private final FakeCluster cluster = new FakeCluster();
    private final AdminAlerts admin = mock(AdminAlerts.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T08:30:00Z"));
    private final KafkaMonitor monitor = new KafkaMonitor(cluster, "eb.", admin, clock);

    @Test
    void unknownUntilTheFirstCheck() {
        assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(monitor.unreachable()).isFalse(); // so publishing is attempted, not refused
    }

    @Test
    void upWhenAllFiveTopicsExist() {
        cluster.deadLetters = 3;

        monitor.probe();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        assertThat(monitor.summary()).isEqualTo("OK");
        assertThat(monitor.deadLetters()).hasValue(3);
    }

    /** Topics are never auto-created, so a missing one is reported, by name, rather than silently made. */
    @Test
    void missingTopicsMakeItDegradedAndAreNamed() {
        cluster.topics.remove("eb.dlq.v1");

        monitor.probe();

        assertThat(monitor.health().getStatus()).isEqualTo(KafkaMonitor.DEGRADED);
        assertThat(monitor.health().getDetails()).containsEntry("missingTopics", List.of("eb.dlq.v1"));
        assertThat(monitor.summary()).isEqualTo("topics missing: eb.dlq.v1");
        assertThat(monitor.unreachable()).isFalse();
        assertThat(monitor.deadLetters()).isEmpty();
    }

    /** Spec section 8: degraded, not down (the app works on), and the admin hears about it after 5 minutes. */
    @Test
    void anOutageIsDegradedAndTheAdminIsToldAfterFiveMinutesOnce() {
        monitor.probe();
        cluster.down = true;

        monitor.probe();
        assertThat(monitor.unreachable()).isTrue();
        assertThat(monitor.health().getStatus()).isEqualTo(KafkaMonitor.DEGRADED);
        assertThat(monitor.summary()).isEqualTo("unreachable since 14:00 IST");
        assertThat(monitor.deadLetters()).isEmpty();

        clock.advance(Duration.ofMinutes(4));
        monitor.probe();
        verify(admin, never()).notifyOncePerDay(anyString(), anyString());

        clock.advance(Duration.ofMinutes(1));
        monitor.probe();
        clock.advance(Duration.ofMinutes(10));
        monitor.probe();
        verify(admin).notifyOncePerDay(eq("kafka-down"), contains("unreachable for 5 minutes (since 14:00 IST)"));

        cluster.down = false;
        monitor.probe();
        assertThat(monitor.unreachable()).isFalse();
        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        verify(admin).notifyOncePerDay(eq("kafka-back"), contains("after 15 minutes"));
    }

    @Test
    void aShortBlipNeverReachesTheAdmin() {
        cluster.down = true;
        monitor.probe();
        clock.advance(Duration.ofMinutes(2));
        cluster.down = false;
        monitor.probe();

        verify(admin, never()).notifyOncePerDay(anyString(), anyString());
    }

    /** How the bus learns it can start consumers that couldn't be created during an outage. */
    @Test
    void reachableCallbacksRunOnlyAfterSuccessfulChecks() {
        AtomicInteger runs = new AtomicInteger();
        monitor.whenReachable(runs::incrementAndGet);

        cluster.down = true;
        monitor.probe();
        assertThat(runs).hasValue(0);

        cluster.down = false;
        monitor.probe();
        assertThat(runs).hasValue(1);
    }
}
