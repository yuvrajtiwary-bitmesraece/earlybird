package io.github.yuvrajtiwary.earlybird.profiles;

import io.github.yuvrajtiwary.earlybird.enrich.GeminiClient;
import io.github.yuvrajtiwary.earlybird.enrich.SkillVocabulary;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Turns cleaned resume text into {@link ResumeFacts} with one schema-constrained Gemini call. The model's
 * answer is treated as untrusted too: skills are normalized through the vocabulary, numbers are clamped,
 * and roles outside the known list are dropped. Nothing it returns can trigger an action.
 */
public class ResumeParser {

    static final int MAX_SKILLS = 40;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final GeminiClient gemini;
    private final SkillVocabulary vocabulary;
    private final Clock clock;
    private final String promptTemplate;

    public ResumeParser(GeminiClient gemini, SkillVocabulary vocabulary, Clock clock) {
        this.gemini = gemini;
        this.vocabulary = vocabulary;
        this.clock = clock;
        this.promptTemplate = readPrompt();
    }

    /** Role categories a user can pick; OTHER is only for postings. */
    static List<RoleCategory> pickableRoles() {
        return Arrays.stream(RoleCategory.values()).filter(r -> r != RoleCategory.OTHER).toList();
    }

    public ResumeFacts parse(String cleanResumeText) {
        JsonNode answer = gemini.generateJson(new GeminiClient.Request(
                "resume", systemInstruction(), cleanResumeText, schema()));
        return toFacts(answer);
    }

    String systemInstruction() {
        return promptTemplate
                .replace("{today}", LocalDate.now(clock.withZone(INDIA)).toString())
                .replace("{roles}", String.join(", ", pickableRoles().stream().map(ResumeParser::db).toList()))
                .replace("{vocabulary}", String.join(", ", vocabulary.canonicalNames()));
    }

    ResumeFacts toFacts(JsonNode answer) {
        List<String> rawSkills = new ArrayList<>();
        answer.path("skills").forEach(s -> rawSkills.add(s.asString("")));
        List<String> skills = vocabulary.normalize(rawSkills, MAX_SKILLS);

        Double years = null;
        JsonNode y = answer.path("years_experience");
        if (y.isNumber()) {
            years = Math.round(Math.clamp(y.asDouble(), 0.0, 40.0) * 10) / 10.0;
        }

        Integer graduation = null;
        JsonNode g = answer.path("graduation_year");
        int thisYear = LocalDate.now(clock.withZone(INDIA)).getYear();
        if (g.isNumber() && g.asInt() >= 1980 && g.asInt() <= thisYear + 6) {
            graduation = g.asInt();
        }

        List<RoleCategory> roles = new ArrayList<>();
        for (JsonNode r : answer.path("suggested_roles")) {
            pickableRoles().stream()
                    .filter(role -> db(role).equals(r.asString("").strip().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .filter(role -> !roles.contains(role))
                    .ifPresent(roles::add);
        }
        return new ResumeFacts(skills, years, graduation, roles.size() > 3 ? roles.subList(0, 3) : roles);
    }

    /** Gemini's OpenAPI-style response schema: the model can only answer in this shape. */
    static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skills", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"), "maxItems", MAX_SKILLS));
        properties.put("years_experience", Map.of("type", "NUMBER", "nullable", true));
        properties.put("graduation_year", Map.of("type", "INTEGER", "nullable", true));
        properties.put("suggested_roles", Map.of("type", "ARRAY", "maxItems", 3, "items",
                Map.of("type", "STRING", "enum", pickableRoles().stream().map(ResumeParser::db).toList())));
        return Map.of("type", "OBJECT", "properties", properties,
                "required", List.of("skills", "years_experience", "graduation_year", "suggested_roles"));
    }

    private static String db(RoleCategory role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    private static String readPrompt() {
        try (InputStream in = ResumeParser.class.getResourceAsStream("/prompts/resume-system.txt")) {
            if (in == null) {
                throw new IllegalStateException("prompts/resume-system.txt not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
