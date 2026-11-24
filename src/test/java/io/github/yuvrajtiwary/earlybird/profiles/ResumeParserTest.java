package io.github.yuvrajtiwary.earlybird.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.enrich.SkillVocabulary;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResumeParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ZoneOffset.UTC);

    private final ResumeParser parser = new ResumeParser(null, SkillVocabulary.fromClasspath(), CLOCK);

    private static JsonNode answer(String json) {
        return JSON.readTree(json);
    }

    @Test
    void normalizesTheRealGeminiAnswer() {
        // The model's text from a real call (fixtures/gemini/resume-response.json): it returned raw aliases.
        JsonNode response = JSON.readTree(Fixtures.read("gemini/resume-response.json"));
        JsonNode modelJson = JSON.readTree(
                response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asString());

        ResumeFacts facts = parser.toFacts(modelJson);

        assertThat(facts.skills()).contains("Java", "JavaScript", "Spring Boot", "Kubernetes", "PostgreSQL",
                "Data Structures and Algorithms", "Amazon EC2");
        assertThat(facts.skills()).doesNotContain("JS", "springboot", "k8s", "DSA");
        assertThat(facts.graduationYear()).isEqualTo(2025);
        assertThat(facts.suggestedRoles()).containsExactly(RoleCategory.BACKEND, RoleCategory.FULLSTACK);
    }

    @Test
    void distrustsTheModelsNumbersAndRoles() {
        ResumeFacts facts = parser.toFacts(answer("""
                {"skills": ["Java", "communication", "Teamwork"], "years_experience": 99.87,
                 "graduation_year": 1850, "suggested_roles": ["backend", "astronaut", "other", "backend"]}"""));

        assertThat(facts.skills()).containsExactly("Java");          // soft skills dropped
        assertThat(facts.yearsExperience()).isEqualTo(40.0);         // clamped
        assertThat(facts.graduationYear()).isNull();                 // implausible
        assertThat(facts.suggestedRoles()).containsExactly(RoleCategory.BACKEND); // unknown, "other", repeats dropped
    }

    @Test
    void missingValuesStayEmpty() {
        ResumeFacts facts = parser.toFacts(answer("""
                {"skills": [], "years_experience": null, "graduation_year": null, "suggested_roles": []}"""));

        assertThat(facts.skills()).isEmpty();
        assertThat(facts.yearsExperience()).isNull();
        assertThat(facts.graduationYear()).isNull();
    }

    @Test
    void thePromptKnowsTodaysDateTheRolesAndTheVocabulary() {
        String prompt = parser.systemInstruction();

        // Without the date, the model counted "Aug 2025 - present" as 0 years (seen in a real call).
        assertThat(prompt).contains("Today's date is 2026-11-24")
                .contains("data_science_ml", "devops_sre_cloud")
                .contains("Spring Boot", "Kubernetes")
                .contains("untrusted")
                .doesNotContain("{today}", "{roles}", "{vocabulary}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaRequiresEveryField() {
        Map<String, Object> schema = ResumeParser.schema();

        assertThat((List<String>) schema.get("required"))
                .containsExactlyInAnyOrder("skills", "years_experience", "graduation_year", "suggested_roles");
    }
}
