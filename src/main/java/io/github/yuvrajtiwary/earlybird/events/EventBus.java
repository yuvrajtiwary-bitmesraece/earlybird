package io.github.yuvrajtiwary.earlybird.events;

import java.util.concurrent.CompletableFuture;

/**
 * How modules talk to each other. Kafka in production ({@code EVENT_BUS=kafka}, Block 5); an in-process
 * bus for local development ({@code EVENT_BUS=memory}). Modules only see this interface, which is what
 * lets the same code run as one container on Render or as separate deployments in Kubernetes.
 */
public interface EventBus {

    /**
     * Publishes an event. Events with the same key are delivered in order to each consumer group.
     *
     * @return completes when the bus has accepted the event (for Kafka: the broker acknowledged it).
     *     The poller waits on it before marking a posting as published (the outbox-lite rule).
     */
    CompletableFuture<Void> publish(Topic topic, String key, EventEnvelope<?> event);

    /**
     * Registers a consumer group. Every group gets its own copy of every event, as with Kafka.
     *
     * @param payloadType events whose payload isn't this type are rejected, not passed to the handler
     */
    <T> void subscribe(Topic topic, String consumerGroup, Class<T> payloadType, EventHandler<T> handler);

    @FunctionalInterface
    interface EventHandler<T> {
        void handle(EventEnvelope<T> event) throws Exception;
    }
}
