package io.github.yuvrajtiwary.earlybird.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process event bus for local development ({@code EVENT_BUS=memory}).
 *
 * <p>It mimics Kafka's delivery model on purpose: each topic has {@value #LANES} "lanes" per consumer group
 * (like 2 partitions), the key picks the lane, and each lane runs one event at a time. So events with the
 * same key arrive in order, and different keys can run in parallel.
 *
 * <p>Development only: events live in memory (lost on restart), events published before anyone subscribes
 * are dropped, and a failing handler is logged, not retried or sent to the dead-letter topic.
 */
public final class InMemoryEventBus implements EventBus, AutoCloseable {

    static final int LANES = 2;

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private record Subscription<T>(
            Topic topic, String group, Class<T> payloadType, EventHandler<T> handler, ExecutorService[] lanes) {}

    private final Map<Topic, List<Subscription<?>>> subscriptions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    @Override
    public CompletableFuture<Void> publish(Topic topic, String key, EventEnvelope<?> event) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("event bus is closed"));
        }
        int lane = Math.floorMod(key.hashCode(), LANES);
        for (Subscription<?> subscription : subscriptions.getOrDefault(topic, List.of())) {
            subscription.lanes()[lane].execute(() -> deliver(subscription, event));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> void subscribe(Topic topic, String consumerGroup, Class<T> payloadType, EventHandler<T> handler) {
        List<Subscription<?>> list = subscriptions.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            if (list.stream().anyMatch(s -> s.group().equals(consumerGroup))) {
                throw new IllegalStateException(consumerGroup + " is already subscribed to " + topic);
            }
            var lanes = new ExecutorService[LANES];
            for (int i = 0; i < LANES; i++) {
                lanes[i] = Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("bus-" + topic + "-" + consumerGroup + "-" + i).factory());
            }
            list.add(new Subscription<>(topic, consumerGroup, payloadType, handler, lanes));
        }
    }

    private <T> void deliver(Subscription<T> subscription, EventEnvelope<?> event) {
        if (!subscription.payloadType().isInstance(event.payload())) {
            log.error("Dropped event {} on {}: {} expects {} but got {}", event.eventId(), subscription.topic(),
                    subscription.group(), subscription.payloadType().getSimpleName(), event.type());
            return;
        }
        @SuppressWarnings("unchecked")
        EventEnvelope<T> typed = (EventEnvelope<T>) event;
        try {
            subscription.handler().handle(typed);
        } catch (Exception e) {
            log.error("Handler {} failed for event {} on {}", subscription.group(), event.eventId(),
                    subscription.topic(), e);
        }
    }

    /** Stops accepting events and lets each lane finish what it has queued (up to 5 seconds). */
    @Override
    public void close() {
        closed = true;
        List<ExecutorService> all = subscriptions.values().stream()
                .flatMap(List::stream)
                .flatMap(s -> List.of(s.lanes()).stream())
                .toList();
        all.forEach(ExecutorService::shutdown);
        for (ExecutorService lane : all) {
            try {
                if (!lane.awaitTermination(5, TimeUnit.SECONDS)) {
                    lane.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
