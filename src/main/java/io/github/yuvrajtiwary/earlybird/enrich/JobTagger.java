package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.common.Cities;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.EmploymentType;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.RemoteScope;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Tagger;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.sources.HtmlText;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tags up to 15 postings with one Gemini call (spec 3.7). Postings are sent as {@code p1..p15} rather than
 * UUIDs: short ids are cheaper and the model can't garble them. The answer is validated field by field; a
 * posting missing from it is simply absent from the result, and the caller retries or falls back.
 */
public class JobTagger {

    static final int MAX_BATCH = 15;
    static final int MAX_DESCRIPTION = 2500;
    static final int MAX_SKILLS = 12;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final GeminiClient gemini;
    private final SkillVocabulary vocabulary;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String promptTemplate = Prompts.read("tagging-system.txt");

    public JobTagger(GeminiClient gemini, SkillVocabulary vocabulary, ObjectMapper mapper, Clock clock) {
        this.gemini = gemini;
        this.vocabulary = vocabulary;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Tags for the postings the model answered for, keyed by posting id. Throws {@link GeminiException}. */
    public Map<UUID, PostingTags> tag(List<Posting> postings) {
        if (postings.isEmpty()) {
            return Map.of();
        }
        if (postings.size() > MAX_BATCH) {
            throw new IllegalArgumentException("at most " + MAX_BATCH + " postings per call");
        }
        Map<String, Posting> byShortId = new LinkedHashMap<>();
        List<Map<String, Object>> input = new ArrayList<>();
        for (int i = 0; i < postings.size(); i++) {
            Posting p = postings.get(i);
            String id = "p" + (i + 1);
            byShortId.put(id, p);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("title", p.title());
            item.put("company", p.company());
            item.put("location", p.locationRaw() == null ? "" : p.locationRaw());
            item.put("description", HtmlText.truncate(p.descriptionText() == null ? "" : p.descriptionText(), MAX_DESCRIPTION));
            input.add(item);
        }
        JsonNode answer = gemini.generateJson(new GeminiClient.Request(
                "tagging", systemInstruction(), mapper.writeValueAsString(input), schema()));

        Map<UUID, PostingTags> tags = new HashMap<>();
        Instant now = clock.instant();
        for (JsonNode item : answer.path("postings")) {
            Posting p = byShortId.get(item.path("id").asString(""));
            if (p != null && !tags.containsKey(p.id())) {
                tags.put(p.id(), toTags(item, p, now));
            }
        }
        return tags;
    }

    String systemInstruction() {
        return promptTemplate
                .replace("{today}", LocalDate.now(clock.withZone(INDIA)).toString())
                .replace("{vocabulary}", String.join(", ", vocabulary.canonicalNames()));
    }

    PostingTags toTags(JsonNode item, Posting p, Instant now) {
        Double min = years(item.path("min_years"));
        Double max = years(item.path("max_years"));
        if (min != null && max != null && max < min) {
            max = null;
        }
        List<String> skills = new ArrayList<>();
        item.path("skills").forEach(s -> skills.add(s.asString("")));
        List<String> cities = new ArrayList<>();
        item.path("cities").forEach(c -> {
            String city = Cities.normalize(c.asString(""));
            if (!city.isBlank() && !cities.contains(city)) {
                cities.add(city);
            }
        });
        List<String> countries = new ArrayList<>();
        item.path("countries").forEach(c -> {
            String code = c.asString("").strip().toUpperCase(Locale.ROOT);
            if (code.matches("[A-Z]{2}") && !countries.contains(code)) {
                countries.add(code);
            }
        });
        String summary = item.path("summary").asString("").strip();
        return new PostingTags(p.id(),
                enumOr(RoleCategory.class, item.path("role_category"), RoleCategory.OTHER),
                enumOr(Seniority.class, item.path("seniority"), Seniority.UNKNOWN),
                min, max,
                item.path("fresher_friendly").asBoolean(false),
                enumOr(EmploymentType.class, item.path("employment_type"), EmploymentType.UNKNOWN),
                enumOr(WorkMode.class, item.path("work_mode"), WorkMode.UNKNOWN),
                enumOr(RemoteScope.class, item.path("remote_scope"), RemoteScope.UNKNOWN),
                cities.size() > 10 ? cities.subList(0, 10) : cities,
                countries,
                vocabulary.normalize(skills, MAX_SKILLS),
                summary.isEmpty() ? null : HtmlText.truncate(summary, 160),
                Tagger.GEMINI, gemini.model(), now);
    }

    private static Double years(JsonNode n) {
        return n.isNumber() ? Math.round(Math.clamp(n.asDouble(), 0.0, 30.0) * 10) / 10.0 : null;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, JsonNode n, E fallback) {
        try {
            return Enum.valueOf(type, n.asString("").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", Map.of("type", "STRING"));
        props.put("role_category", enumSchema(RoleCategory.class));
        props.put("seniority", enumSchema(Seniority.class));
        props.put("min_years", Map.of("type", "NUMBER", "nullable", true));
        props.put("max_years", Map.of("type", "NUMBER", "nullable", true));
        props.put("fresher_friendly", Map.of("type", "BOOLEAN"));
        props.put("employment_type", enumSchema(EmploymentType.class));
        props.put("work_mode", enumSchema(WorkMode.class));
        props.put("remote_scope", enumSchema(RemoteScope.class));
        props.put("cities", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
        props.put("countries", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
        props.put("skills", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
        props.put("summary", Map.of("type", "STRING"));
        Map<String, Object> posting = Map.of("type", "OBJECT", "properties", props, "required", List.copyOf(props.keySet()));
        // No maxItems anywhere: with them, Gemini refused this schema in a real call ("too many states for
        // serving"; nested length limits multiply the constrained-decoding states). Batch size and the 12-skill
        // cap are enforced in Java instead.
        return Map.of("type", "OBJECT",
                "properties", Map.of("postings", Map.of("type", "ARRAY", "items", posting)),
                "required", List.of("postings"));
    }

    private static <E extends Enum<E>> Map<String, Object> enumSchema(Class<E> type) {
        return Map.of("type", "STRING", "enum",
                Arrays.stream(type.getEnumConstants()).map(e -> e.name().toLowerCase(Locale.ROOT)).toList());
    }
}
