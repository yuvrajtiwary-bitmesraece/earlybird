package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The wrapper every event travels in (spec section 3.2).
 *
 * @param eventId    UUID v7; consumers de-duplicate on (eventId, consumer) in {@code processed_events}
 * @param type       payload type name, e.g. {@code PostingDiscovered}
 * @param version    payload schema version
 * @param occurredAt when the producer created the event
 * @param producer   the module that published it, e.g. {@code poller}
 * @param traceId    the same value along one posting's whole journey (discovered → enriched → alerts),
 *                   so logs from every stage can be joined
 */
public record EventEnvelope<T>(
        UUID eventId, String type, int version, Instant occurredAt, String producer, String traceId, T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(payload, "payload");
    }

    /** Starts a new trace: the first event about something. */
    public static <T> EventEnvelope<T> of(T payload, String producer, Clock clock) {
        UUID id = Ids.newId();
        return new EventEnvelope<>(id, typeOf(payload), 1, clock.instant(), producer, id.toString(), payload);
    }

    /** A follow-up event caused by this one: new id, same trace. */
    public <R> EventEnvelope<R> derive(R newPayload, String newProducer, Clock clock) {
        return new EventEnvelope<>(
                Ids.newId(), typeOf(newPayload), 1, clock.instant(), newProducer, traceId, newPayload);
    }

    private static String typeOf(Object payload) {
        return Objects.requireNonNull(payload, "payload").getClass().getSimpleName();
    }
}
