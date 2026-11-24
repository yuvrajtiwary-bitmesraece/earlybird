package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InMemoryEventBusTest {

    record Ping(String key, int n) {}

    private final Clock clock = Clock.systemUTC();
    private final InMemoryEventBus bus = new InMemoryEventBus();

    @AfterEach
    void closeBus() {
        bus.close();
    }

    private void publish(Topic topic, Ping ping) {
        bus.publish(topic, ping.key(), EventEnvelope.of(ping, "test", clock)).join();
    }

    @Test
    void everyConsumerGroupGetsItsOwnCopy() {
        var enricher = new CopyOnWriteArrayList<Integer>();
        var audit = new CopyOnWriteArrayList<Integer>();
        bus.subscribe(Topic.JOBS_DISCOVERED, "eb-enricher", Ping.class, e -> enricher.add(e.payload().n()));
        bus.subscribe(Topic.JOBS_DISCOVERED, "eb-audit", Ping.class, e -> audit.add(e.payload().n()));

        publish(Topic.JOBS_DISCOVERED, new Ping("posting-1", 1));

        await().untilAsserted(() -> {
            assertThat(enricher).containsExactly(1);
            assertThat(audit).containsExactly(1);
        });
    }

    @Test
    void eventsWithTheSameKeyArriveInOrder() {
        Map<String, List<Integer>> seen = new ConcurrentHashMap<>();
        bus.subscribe(Topic.ALERTS_REQUESTED, "eb-notifier", Ping.class, e -> {
            Thread.sleep(1); // give a wrong implementation the chance to reorder
            seen.computeIfAbsent(e.payload().key(), k -> new CopyOnWriteArrayList<>()).add(e.payload().n());
        });

        for (int n = 0; n < 60; n++) {
            publish(Topic.ALERTS_REQUESTED, new Ping("user-" + (n % 3), n));
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(seen.values().stream().mapToInt(List::size).sum()).isEqualTo(60);
            seen.values().forEach(list -> assertThat(list).isSorted());
        });
    }

    @Test
    void onlySubscribersOfThatTopicReceiveIt() {
        var closed = new CopyOnWriteArrayList<Integer>();
        var enriched = new CopyOnWriteArrayList<Integer>();
        bus.subscribe(Topic.JOBS_CLOSED, "eb-matcher", Ping.class, e -> closed.add(e.payload().n()));
        bus.subscribe(Topic.JOBS_ENRICHED, "eb-matcher", Ping.class, e -> enriched.add(e.payload().n()));

        publish(Topic.JOBS_ENRICHED, new Ping("posting-1", 7));

        await().untilAsserted(() -> assertThat(enriched).containsExactly(7));
        assertThat(closed).isEmpty();
    }

    @Test
    void aFailingHandlerDoesNotBlockLaterEvents() {
        var handled = new CopyOnWriteArrayList<Integer>();
        bus.subscribe(Topic.JOBS_ENRICHED, "eb-matcher", Ping.class, e -> {
            if (e.payload().n() == 1) {
                throw new IllegalStateException("boom");
            }
            handled.add(e.payload().n());
        });

        publish(Topic.JOBS_ENRICHED, new Ping("posting-1", 1));
        publish(Topic.JOBS_ENRICHED, new Ping("posting-1", 2));

        await().untilAsserted(() -> assertThat(handled).containsExactly(2));
    }

    @Test
    void aPayloadOfTheWrongTypeIsNotPassedToTheHandler() {
        var handled = new CopyOnWriteArrayList<Integer>();
        bus.subscribe(Topic.JOBS_ENRICHED, "eb-matcher", Ping.class, e -> handled.add(e.payload().n()));

        bus.publish(Topic.JOBS_ENRICHED, "posting-1", EventEnvelope.of("not a Ping", "test", clock)).join();
        publish(Topic.JOBS_ENRICHED, new Ping("posting-1", 2));

        await().untilAsserted(() -> assertThat(handled).containsExactly(2));
    }

    @Test
    void aGroupCannotSubscribeTwiceToOneTopic() {
        bus.subscribe(Topic.DLQ, "eb-admin", Ping.class, e -> {});
        assertThatThrownBy(() -> bus.subscribe(Topic.DLQ, "eb-admin", Ping.class, e -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishingAfterCloseFails() {
        bus.close();
        assertThat(bus.publish(Topic.DLQ, "k", EventEnvelope.of(new Ping("k", 1), "test", clock)))
                .isCompletedExceptionally();
    }

    @Test
    void aDerivedEventKeepsTheTraceButGetsANewId() {
        EventEnvelope<Ping> discovered = EventEnvelope.of(new Ping("posting-1", 1), "poller", clock);
        EventEnvelope<Ping> enriched = discovered.derive(new Ping("posting-1", 2), "enricher", clock);

        assertThat(enriched.traceId()).isEqualTo(discovered.traceId());
        assertThat(enriched.eventId()).isNotEqualTo(discovered.eventId());
        assertThat(enriched.producer()).isEqualTo("enricher");
        assertThat(enriched.type()).isEqualTo("Ping");
        assertThat(enriched.version()).isEqualTo(1);
    }

    @Test
    void topicNamesUseTheConfiguredPrefix() {
        assertThat(Topic.JOBS_DISCOVERED.topicName("eb.")).isEqualTo("eb.jobs.discovered.v1");
        assertThat(Topic.DLQ.topicName("eb.")).isEqualTo("eb.dlq.v1");
    }
}
