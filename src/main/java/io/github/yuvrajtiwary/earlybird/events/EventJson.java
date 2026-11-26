package io.github.yuvrajtiwary.earlybird.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events on the wire: the spec 3.2 envelope as UTF-8 JSON, for example
 * <pre>{"eventId":"…","type":"PostingClosed","version":1,"occurredAt":"2026-11-24T08:30:00Z",
 *  "producer":"poller","traceId":"…","payload":{"postingId":"…","closedAt":"2026-11-24T08:30:00Z"}}</pre>
 *
 * <p>It has its own mapper, so changing the app's web JSON settings can't change the event format by accident.
 * Unknown fields are ignored, so a producer can add a field before every consumer knows about it. Anything else
 * that doesn't fit (not JSON, a missing field, another payload type, an unknown version) is a poison event:
 * retrying can't fix it, so reading it throws {@link DeadLetterException} and it goes straight to the
 * dead-letter topic.
 */
public final class EventJson {

    /** Breaking payload changes get a new topic ({@code .v2}), so a v1 topic only ever carries version 1. */
    static final int VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public byte[] write(EventEnvelope<?> event) {
        return mapper.writeValueAsBytes(event);
    }

    public <T> EventEnvelope<T> read(byte[] bytes, Class<T> payloadType) {
        String expected = payloadType.getSimpleName();
        if (bytes == null || bytes.length == 0) {
            throw new DeadLetterException("empty message, expected a " + expected + " event");
        }
        JsonNode tree;
        try {
            tree = mapper.readTree(bytes);
        } catch (JacksonException e) {
            throw new DeadLetterException("not JSON, expected a " + expected + " event: " + e.getOriginalMessage(), e);
        }
        // Type and version first, so a dead letter says plainly what arrived instead of which field didn't fit.
        JsonNode type = tree == null ? null : tree.get("type");
        if (type == null || !expected.equals(type.asString())) {
            throw new DeadLetterException("expected a " + expected + " event, got "
                    + (type == null ? "no type" : type.asString()));
        }
        JsonNode version = tree.get("version");
        if (version == null || !version.isInt() || version.intValue() != VERSION) {
            throw new DeadLetterException(expected + " version " + version + " on a v" + VERSION + " topic");
        }
        JavaType envelope = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        try {
            return mapper.treeToValue(tree, envelope);
        } catch (JacksonException e) {
            throw new DeadLetterException("not a valid " + expected + " event: " + e.getOriginalMessage(), e);
        }
    }
}
