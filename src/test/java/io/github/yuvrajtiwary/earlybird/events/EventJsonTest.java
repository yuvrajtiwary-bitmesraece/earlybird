package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EventJsonTest {

    private static final UUID EVENT = UUID.fromString("019abcde-0000-7000-8000-000000000001");
    private static final UUID POSTING = UUID.fromString("019abcde-0000-7000-8000-000000000002");
    private static final Instant AT = Instant.parse("2026-11-24T08:30:00Z");

    private final EventJson json = new EventJson();

    private static <T> EventEnvelope<T> envelope(T payload) {
        return new EventEnvelope<>(EVENT, payload.getClass().getSimpleName(), 1, AT, "poller", EVENT.toString(), payload);
    }

    /** The exact shape from spec 3.2, with times as ISO-8601 text (not epoch numbers). */
    @Test
    void writesTheSpecEnvelope() {
        byte[] bytes = json.write(envelope(new PostingClosed(POSTING, AT)));

        JsonNode tree = JsonMapper.builder().build().readTree(bytes);
        assertThat(tree.propertyNames()).containsExactlyInAnyOrder(
                "eventId", "type", "version", "occurredAt", "producer", "traceId", "payload");
        assertThat(tree.get("eventId").asString()).isEqualTo(EVENT.toString());
        assertThat(tree.get("type").asString()).isEqualTo("PostingClosed");
        assertThat(tree.get("version").asInt()).isEqualTo(1);
        assertThat(tree.get("occurredAt").asString()).isEqualTo("2026-11-24T08:30:00Z");
        assertThat(tree.get("producer").asString()).isEqualTo("poller");
        assertThat(tree.get("payload").get("postingId").asString()).isEqualTo(POSTING.toString());
        assertThat(tree.get("payload").get("closedAt").asString()).isEqualTo("2026-11-24T08:30:00Z");
    }

    @Test
    void everyPayloadSurvivesTheRoundTrip() {
        var discovered = new PostingDiscovered(POSTING, "greenhouse", "acme", "Acme", "123", "Backend Engineer",
                "https://jobs.example.com/123", "Bengaluru", List.of("Engineering"), null, AT, "hash",
                "Build APIs in Java. Ünïcödé ✓", false);
        var enriched = new PostingEnriched(POSTING, "backend", "junior", 0.0, 2.0, true, "full_time", "hybrid",
                "unknown", List.of("Bengaluru"), List.of("IN"), List.of("Java", "Kafka"), "gemini");
        var requested = new AlertRequested(EVENT, 42L, POSTING, 84, List.of("Java"), List.of("Kafka"), false);
        var dead = new DeadLetter("eb.jobs.enriched.v1", 1, 77L, POSTING.toString(), "IllegalStateException: boom", 4,
                AT, "bm90IGpzb24=");

        assertThat(json.read(json.write(envelope(discovered)), PostingDiscovered.class).payload()).isEqualTo(discovered);
        assertThat(json.read(json.write(envelope(enriched)), PostingEnriched.class).payload()).isEqualTo(enriched);
        assertThat(json.read(json.write(envelope(requested)), AlertRequested.class).payload()).isEqualTo(requested);
        assertThat(json.read(json.write(envelope(dead)), DeadLetter.class).payload()).isEqualTo(dead);
        assertThat(json.read(json.write(envelope(dead)), DeadLetter.class)).isEqualTo(envelope(dead));
    }

    /** A newer producer may add fields before every consumer knows them. */
    @Test
    void ignoresFieldsItDoesNotKnow() {
        String text = new String(json.write(envelope(new PostingClosed(POSTING, AT))), StandardCharsets.UTF_8)
                .replace("\"producer\":", "\"region\":\"ap-south\",\"producer\":")
                .replace("\"closedAt\":", "\"reason\":\"filled\",\"closedAt\":");

        assertThat(json.read(text.getBytes(StandardCharsets.UTF_8), PostingClosed.class).payload())
                .isEqualTo(new PostingClosed(POSTING, AT));
    }

    /** Poison: retrying can't fix any of these, so they must go straight to the dead-letter topic. */
    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not json at all",
        "{\"eventId\": \"019abcde-0000-7000-8000-000000000001\"}",
        "{\"eventId\":\"019abcde-0000-7000-8000-000000000001\",\"type\":\"PostingClosed\",\"version\":1,"
                + "\"occurredAt\":\"2026-11-24T08:30:00Z\",\"producer\":\"poller\",\"traceId\":\"t\",\"payload\":null}",
        "{\"eventId\":\"019abcde-0000-7000-8000-000000000001\",\"type\":\"PostingClosed\",\"version\":1,"
                + "\"occurredAt\":\"yesterday\",\"producer\":\"poller\",\"traceId\":\"t\",\"payload\":{}}",
    })
    void malformedEventsArePoison(String text) {
        assertThatThrownBy(() -> json.read(text.getBytes(StandardCharsets.UTF_8), PostingClosed.class))
                .isInstanceOf(DeadLetterException.class);
    }

    @Test
    void anotherPayloadTypeIsPoison() {
        byte[] closed = json.write(envelope(new PostingClosed(POSTING, AT)));

        assertThatThrownBy(() -> json.read(closed, AlertRequested.class))
                .isInstanceOf(DeadLetterException.class)
                .hasMessageContaining("expected a AlertRequested event, got PostingClosed");
    }

    /** Breaking changes go to a new .v2 topic, so version 2 on a v1 topic is a mistake, not something to guess at. */
    @Test
    void anUnknownVersionIsPoison() {
        var v2 = new EventEnvelope<>(EVENT, "PostingClosed", 2, AT, "poller", "t", new PostingClosed(POSTING, AT));

        assertThatThrownBy(() -> json.read(json.write(v2), PostingClosed.class))
                .isInstanceOf(DeadLetterException.class)
                .hasMessageContaining("version 2");
    }
}
