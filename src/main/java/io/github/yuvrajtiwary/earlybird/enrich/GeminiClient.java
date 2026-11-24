package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.enrich.GeminiException.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini's REST API ({@code models/{model}:generateContent}) with schema-constrained JSON output.
 *
 * <p>Plain REST instead of Google's Java SDK: the SDK brings Jackson 2 and its own HTTP stack next to
 * Spring Boot 4's Jackson 3, and a plain HTTP API is easy to fake in tests. The key travels in the
 * {@code x-goog-api-key} header, never in the URL, so it can't end up in logs.
 *
 * <p>Every attempt is counted against {@link GeminiBudget} first. 429 and 5xx are retried with backoff and
 * jitter, at most {@value #MAX_ATTEMPTS} attempts in total, waiting as long as Google's RetryInfo asks (up to
 * a cap). Temperature 0: the same input should give the same tags.
 */
public class GeminiClient {

    static final int MAX_ATTEMPTS = 3;

    /** One structured-output request. {@code responseSchema} uses Gemini's OpenAPI-style schema. */
    public record Request(String purpose, String systemInstruction, String userText, Map<String, Object> responseSchema) {}

    private final RestClient http;
    private final ObjectMapper mapper;
    private final GeminiBudget budget;
    private final MeterRegistry meters;
    private final URI baseUrl;
    private final String apiKey;
    private final String model;
    private final List<Duration> backoff;
    private final Duration maxRetryWait;

    public GeminiClient(ObjectMapper mapper, GeminiBudget budget, MeterRegistry meters, URI baseUrl, String apiKey,
            String model) {
        this(mapper, budget, meters, baseUrl, apiKey, model,
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(5)), Duration.ofSeconds(20), Duration.ofSeconds(60));
    }

    GeminiClient(ObjectMapper mapper, GeminiBudget budget, MeterRegistry meters, URI baseUrl, String apiKey,
            String model, List<Duration> backoff, Duration maxRetryWait, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        factory.enableCompression(true);
        this.http = RestClient.builder().requestFactory(factory).build();
        this.mapper = mapper;
        this.budget = budget;
        this.meters = meters;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.backoff = List.copyOf(backoff);
        this.maxRetryWait = maxRetryWait;
    }

    public String model() {
        return model;
    }

    /** Sends the request and returns the model's JSON answer. */
    public JsonNode generateJson(Request request) {
        String body = mapper.writeValueAsString(Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.userText())))),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "responseMimeType", "application/json",
                        "responseSchema", request.responseSchema())));
        URI uri = UriComponentsBuilder.fromUri(baseUrl)
                .path("/v1beta/models/{model}:generateContent")
                .buildAndExpand(model)
                .encode()
                .toUri();

        GeminiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (!budget.tryConsume()) {
                record(request.purpose(), "budget_exhausted");
                throw new GeminiException(Kind.BUDGET_EXHAUSTED, "daily Gemini budget used up");
            }
            Timer.Sample timer = Timer.start(meters);
            try {
                JsonNode answer = once(uri, body);
                timer.stop(meters.timer("earlybird.gemini", "purpose", request.purpose()));
                record(request.purpose(), "ok");
                return answer;
            } catch (RetryableFailure e) {
                timer.stop(meters.timer("earlybird.gemini", "purpose", request.purpose()));
                last = new GeminiException(Kind.UNAVAILABLE, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(waitBefore(attempt, e.retryAfter));
                }
            } catch (GeminiException e) {
                timer.stop(meters.timer("earlybird.gemini", "purpose", request.purpose()));
                record(request.purpose(), e.kind().name().toLowerCase());
                throw e;
            }
        }
        record(request.purpose(), "unavailable");
        throw last;
    }

    private JsonNode once(URI uri, String body) {
        try {
            return http.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-goog-api-key", apiKey)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 200) {
                            return answerOf(mapper.readTree(response.getBody()));
                        }
                        // Error bodies are usually Google's JSON, but a proxy's 502 can be an HTML page.
                        JsonNode error = lenientJson(response.getBody().readAllBytes());
                        String message = "Gemini " + status + " " + error.path("error").path("status").asString("");
                        if (status == 429 || status >= 500) {
                            throw new RetryableFailure(message, retryDelay(error));
                        }
                        throw new GeminiException(Kind.REJECTED, message);
                    }, true);
        } catch (RetryableFailure | GeminiException e) {
            throw e;
        } catch (JacksonException e) {
            throw new GeminiException(Kind.BAD_OUTPUT, "unreadable response from Gemini", e);
        } catch (RestClientException e) {
            // Timeouts and connection errors. The message is ours: the exception's own text includes the URL.
            throw new RetryableFailure("network error calling Gemini: "
                    + e.getMostSpecificCause().getClass().getSimpleName(), null);
        }
    }

    /** The JSON the model produced, from candidates[0].content.parts[0].text. */
    private JsonNode answerOf(JsonNode response) {
        JsonNode candidate = response.path("candidates").path(0);
        String finish = candidate.path("finishReason").asString("");
        if (!"STOP".equals(finish)) {
            throw new GeminiException(Kind.BAD_OUTPUT, "Gemini stopped with finishReason=" + finish);
        }
        String text = candidate.path("content").path("parts").path(0).path("text").asString("");
        try {
            JsonNode answer = mapper.readTree(text);
            if (!answer.isObject()) {
                throw new GeminiException(Kind.BAD_OUTPUT, "Gemini answer is not a JSON object");
            }
            return answer;
        } catch (JacksonException e) {
            throw new GeminiException(Kind.BAD_OUTPUT, "Gemini answer is not valid JSON", e);
        }
    }

    private JsonNode lenientJson(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (JacksonException notJson) {
            return mapper.createObjectNode();
        }
    }

    /** Google's RetryInfo detail, e.g. {"@type": ".../google.rpc.RetryInfo", "retryDelay": "12s"}. */
    private static Duration retryDelay(JsonNode error) {
        for (JsonNode detail : error.path("error").path("details")) {
            if (detail.path("@type").asString("").endsWith("google.rpc.RetryInfo")) {
                String delay = detail.path("retryDelay").asString("");
                if (delay.endsWith("s")) {
                    try {
                        return Duration.ofMillis((long) (Double.parseDouble(delay.substring(0, delay.length() - 1)) * 1000));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private Duration waitBefore(int attempt, Duration retryAfter) {
        Duration base = retryAfter != null ? retryAfter : backoff.get(Math.min(attempt - 1, backoff.size() - 1));
        long jitter = base.isZero() ? 0 : ThreadLocalRandom.current().nextLong(base.toMillis() / 4 + 1);
        Duration wait = base.plusMillis(jitter);
        return wait.compareTo(maxRetryWait) > 0 ? maxRetryWait : wait;
    }

    private void record(String purpose, String result) {
        meters.counter("earlybird.gemini.calls", "purpose", purpose, "result", result).increment();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiException(Kind.UNAVAILABLE, "interrupted");
        }
    }

    /** Internal: a failure worth retrying. */
    private static final class RetryableFailure extends RuntimeException {
        private final Duration retryAfter;

        RetryableFailure(String message, Duration retryAfter) {
            super(message, null, false, false);
            this.retryAfter = retryAfter;
        }
    }
}
