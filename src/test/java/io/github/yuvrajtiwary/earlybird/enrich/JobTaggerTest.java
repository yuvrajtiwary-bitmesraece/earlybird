package io.github.yuvrajtiwary.earlybird.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

class JobTaggerTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ZoneOffset.UTC);

    private final GeminiBudget unlimited = new GeminiBudget(null, CLOCK, 100) {
        @Override
        public boolean tryConsume() {
            return true;
        }
    };
    private final JobTagger tagger = new JobTagger(
            new GeminiClient(JSON, unlimited, new SimpleMeterRegistry(), URI.create(wm.baseUrl()), "k", "gemini-test",
                    List.of(Duration.ZERO), Duration.ZERO, Duration.ofSeconds(5)),
            SkillVocabulary.fromClasspath(), JSON, CLOCK);

    private static Posting posting(String title, String description) {
        return new Posting(Ids.newId(), 1, Source.GREENHOUSE, "x", "https://x", title, "Acme", "Bengaluru, India",
                List.of(), null, CLOCK.instant(), CLOCK.instant(), 0, null, "h", description, false, null, false);
    }

    /** Wraps the model's JSON the way Gemini returns it. */
    private static String geminiReturns(String modelJson) {
        return JSON.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "finishReason", "STOP", "content", Map.of("parts", List.of(Map.of("text", modelJson)))))));
    }

    @Test
    void mapsShortIdsBackToPostingsAndValidatesEveryField() {
        Posting backend = posting("Backend Engineer I", "Java, Spring Boot, k8s. 0-2 years.");
        Posting sales = posting("Account Executive", "Sell things.");
        wm.stubFor(post(urlPathMatching(".*generateContent")).willReturn(okJson(geminiReturns("""
                {"postings": [
                  {"id": "p1", "role_category": "backend", "seniority": "junior", "min_years": 0, "max_years": 2,
                   "fresher_friendly": true, "employment_type": "full_time", "work_mode": "hybrid", "remote_scope": "unknown",
                   "cities": ["Bangalore"], "countries": ["in", "INDIA"], "skills": ["Java", "springboot", "k8s", "teamwork"],
                   "summary": "Builds backend services."},
                  {"id": "p2", "role_category": "astronaut", "seniority": "senior", "min_years": 99, "max_years": 3,
                   "fresher_friendly": false, "employment_type": "full_time", "work_mode": "onsite", "remote_scope": "unknown",
                   "cities": [], "countries": [], "skills": [], "summary": ""},
                  {"id": "p9", "role_category": "backend", "seniority": "junior", "min_years": null, "max_years": null,
                   "fresher_friendly": true, "employment_type": "full_time", "work_mode": "onsite", "remote_scope": "unknown",
                   "cities": [], "countries": [], "skills": [], "summary": "Not one of ours."}
                ]}"""))));

        Map<UUID, PostingTags> tags = tagger.tag(List.of(backend, sales));

        assertThat(tags).containsOnlyKeys(backend.id(), sales.id()); // "p9" isn't in the batch: ignored
        PostingTags b = tags.get(backend.id());
        assertThat(b.roleCategory()).isEqualTo(RoleCategory.BACKEND);
        assertThat(b.workMode()).isEqualTo(WorkMode.HYBRID);
        assertThat(b.cities()).containsExactly("Bengaluru");          // canonical spelling
        assertThat(b.countries()).containsExactly("IN");              // "INDIA" isn't an ISO code: dropped
        assertThat(b.skills()).containsExactly("Java", "Spring Boot", "Kubernetes"); // normalized, soft skill dropped
        assertThat(b.model()).isEqualTo("gemini-test");

        PostingTags s = tags.get(sales.id());
        assertThat(s.roleCategory()).isEqualTo(RoleCategory.OTHER);  // unknown enum value
        assertThat(s.seniority()).isEqualTo(Seniority.SENIOR);
        assertThat(s.minYears()).isEqualTo(30.0);                     // clamped
        assertThat(s.maxYears()).isNull();                            // max < min: dropped
        assertThat(s.summary()).isNull();
    }

    @Test
    void aPostingTheModelLeftOutIsMissingFromTheResult() {
        Posting a = posting("A", "a");
        Posting b = posting("B", "b");
        wm.stubFor(post(urlPathMatching(".*generateContent")).willReturn(okJson(geminiReturns("""
                {"postings": [{"id": "p2", "role_category": "backend", "seniority": "unknown", "min_years": null,
                  "max_years": null, "fresher_friendly": false, "employment_type": "unknown", "work_mode": "unknown",
                  "remote_scope": "unknown", "cities": [], "countries": [], "skills": [], "summary": "B."}]}"""))));

        assertThat(tagger.tag(List.of(a, b))).containsOnlyKeys(b.id());
    }

    @Test
    void sendsShortIdsTrimmedDescriptionsAndTheVocabulary() {
        wm.stubFor(post(urlPathMatching(".*generateContent")).willReturn(okJson(geminiReturns("{\"postings\": []}"))));

        tagger.tag(List.of(posting("Backend Engineer", "x".repeat(5000))));

        wm.verify(postRequestedFor(urlPathMatching(".*generateContent"))
                .withRequestBody(containing("\\\"id\\\":\\\"p1\\\""))
                .withRequestBody(containing("Today's date is 2026-11-24"))
                .withRequestBody(containing("Spring Boot"))
                .withRequestBody(containing("untrusted")));
        String body = wm.findAll(postRequestedFor(urlPathMatching(".*generateContent"))).getLast().getBodyAsString();
        assertThat(body).doesNotContain("x".repeat(JobTagger.MAX_DESCRIPTION + 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaRequiresEveryTagField() {
        Map<String, Object> postings = (Map<String, Object>) ((Map<String, Object>) JobTagger.schema().get("properties")).get("postings");
        Map<String, Object> item = (Map<String, Object>) postings.get("items");

        assertThat((List<String>) item.get("required")).contains("id", "role_category", "seniority", "min_years",
                "fresher_friendly", "work_mode", "remote_scope", "cities", "skills", "summary");
    }
}
