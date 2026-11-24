package io.github.yuvrajtiwary.earlybird.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.enrich.GeminiException.Kind;
import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/v1beta/models/gemini-test:generateContent";
    private static final String KEY = "test-key-123";

    /** A budget without a database: counts calls, refuses after {@code max}. */
    private static final class CountingBudget extends GeminiBudget {
        final AtomicInteger used = new AtomicInteger();
        final int max;

        CountingBudget(int max) {
            super(null, Clock.systemUTC(), max);
            this.max = max;
        }

        @Override
        public boolean tryConsume() {
            return used.incrementAndGet() <= max;
        }
    }

    private final CountingBudget budget = new CountingBudget(10);
    private final GeminiClient client = new GeminiClient(JsonMapper.builder().build(), budget, new SimpleMeterRegistry(),
            URI.create(wm.baseUrl()), KEY, "gemini-test", List.of(Duration.ZERO), Duration.ofMillis(50),
            Duration.ofSeconds(5));

    private static final GeminiClient.Request REQUEST = new GeminiClient.Request(
            "resume", "Extract facts.", "Java developer", Map.of("type", "OBJECT"));

    @Test
    void sendsAStructuredRequestAndReturnsTheModelsJson() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(Fixtures.read("gemini/resume-response.json"))));

        JsonNode answer = client.generateJson(REQUEST);

        assertThat(answer.path("graduation_year").asInt()).isEqualTo(2025);
        wm.verify(postRequestedFor(urlEqualTo(PATH)) // the key is a header, never in the URL
                .withHeader("x-goog-api-key", equalTo(KEY))
                .withRequestBody(matchingJsonPath("$.generationConfig.temperature", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.generationConfig.responseMimeType", equalTo("application/json")))
                .withRequestBody(matchingJsonPath("$.generationConfig.responseSchema.type", equalTo("OBJECT")))
                .withRequestBody(matchingJsonPath("$.systemInstruction.parts[0].text", equalTo("Extract facts.")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", equalTo("Java developer"))));
        assertThat(budget.used).hasValue(1);
    }

    @Test
    void retriesWhenGeminiIsOverloaded() {
        // The real 503 Gemini returned while this was being built.
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("busy").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503).withBody(Fixtures.read("gemini/error-503.json")))
                .willSetStateTo("ok"));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("busy").whenScenarioStateIs("ok")
                .willReturn(okJson(Fixtures.read("gemini/resume-response.json"))));

        assertThat(client.generateJson(REQUEST).has("skills")).isTrue();
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(budget.used).as("every attempt counts against the budget").hasValue(2);
    }

    @Test
    void honoursRetryInfoOn429() {
        String quota = """
                {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "details": [
                  {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "0.01s"}]}}""";
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("quota").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withBody(quota)).willSetStateTo("ok"));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("quota").whenScenarioStateIs("ok")
                .willReturn(okJson(Fixtures.read("gemini/resume-response.json"))));

        assertThat(client.generateJson(REQUEST).has("skills")).isTrue();
    }

    @Test
    void retriesAProxysHtmlErrorPage() {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("proxy").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(502).withBody("<html>Bad Gateway</html>")).willSetStateTo("ok"));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("proxy").whenScenarioStateIs("ok")
                .willReturn(okJson(Fixtures.read("gemini/resume-response.json"))));

        assertThat(client.generateJson(REQUEST).has("skills")).isTrue();
    }

    @Test
    void givesUpAfterThreeAttempts() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503).withBody("{}")));

        assertThatThrownBy(() -> client.generateJson(REQUEST))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        wm.verify(GeminiClient.MAX_ATTEMPTS, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void doesNotRetryABadRequest() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(badRequest().withBody("{\"error\": {\"status\": \"INVALID_ARGUMENT\"}}")));

        assertThatThrownBy(() -> client.generateJson(REQUEST))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void sendsNothingWhenTheBudgetIsUsedUp() {
        var broke = new GeminiClient(JsonMapper.builder().build(), new CountingBudget(0), new SimpleMeterRegistry(),
                URI.create(wm.baseUrl()), KEY, "gemini-test");

        assertThatThrownBy(() -> broke.generateJson(REQUEST))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BUDGET_EXHAUSTED));
        wm.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aBlockedOrCutOffAnswerIsBadOutput() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"candidates": [{"finishReason": "SAFETY", "content": {"parts": []}}]}""")));

        assertThatThrownBy(() -> client.generateJson(REQUEST))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_OUTPUT));
    }
}
