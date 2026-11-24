package io.github.yuvrajtiwary.earlybird.sources;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Streaming JSON reading. Some boards are huge (OpenAI's Ashby board is ~14 MB). Reading the whole response
 * into one tree would need many times that in heap on a 512 MB instance, so jobs are read one at a time and
 * each is discarded after it is converted.
 */
final class JsonStream {

    private JsonStream() {}

    /**
     * Calls {@code action} for each object in an array: the root array if {@code arrayField} is null,
     * otherwise the array under that top-level field.
     */
    static void forEachInArray(ObjectMapper mapper, InputStream in, String arrayField, Consumer<JsonNode> action) {
        try (JsonParser parser = mapper.createParser(in)) {
            JsonToken token = parser.nextToken();
            if (arrayField == null) {
                expect(token == JsonToken.START_ARRAY, "expected a JSON array");
            } else {
                expect(token == JsonToken.START_OBJECT, "expected a JSON object");
                boolean found = false;
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if (arrayField.equals(name)) {
                        expect(value == JsonToken.START_ARRAY, "'" + arrayField + "' is not an array");
                        found = true;
                        break;
                    }
                    parser.skipChildren();
                }
                expect(found, "no '" + arrayField + "' array in the response");
            }
            // Jackson 3 fails on "trailing tokens" by default, i.e. if anything follows the value it reads.
            // Here something always follows (the next job), so that check is off for these partial reads.
            ObjectReader oneJob = mapper.readerFor(JsonNode.class)
                    .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                action.accept(oneJob.readValue(parser));
            }
        }
    }

    /** A field as text, or null if it's missing or JSON null. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    /** The {@code name} of each object in an array field, e.g. Greenhouse departments. */
    static List<String> names(JsonNode node, String field) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : node.path(field)) {
            String name = text(item, "name");
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** ISO-8601 with an offset ("2026-11-16T16:08:54-05:00"), or null if absent or malformed. */
    static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new SourceException(SourceException.Kind.BAD_RESPONSE, 200, message);
        }
    }
}
