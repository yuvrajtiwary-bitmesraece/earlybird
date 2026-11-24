package io.github.yuvrajtiwary.earlybird.sources;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.sources.SourceException.Kind;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SourceHttpTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final SourceHttp http = TestHttp.fast();

    private URI uri(String path) {
        return URI.create(wm.baseUrl() + path);
    }

    private static String asString(InputStream body) throws java.io.IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void identifiesItselfAndAsksForCompression() {
        wm.stubFor(get(urlEqualTo("/ok")).willReturn(okJson("{}")));

        http.get(uri("/ok"), null, SourceHttpTest::asString);

        wm.verify(getRequestedFor(urlEqualTo("/ok"))
                .withHeader("User-Agent", equalTo(TestHttp.USER_AGENT))
                .withHeader("Accept-Encoding", containing("gzip")));
    }

    @Test
    void readsGzipCompressedResponses() {
        String big = "{\"jobs\": [" + "{\"id\": 1},".repeat(2000) + "{\"id\": 2}]}";
        wm.stubFor(get(urlEqualTo("/big")).willReturn(okJson(big))); // WireMock gzips when the client accepts it

        var result = http.get(uri("/big"), null, SourceHttpTest::asString);

        assertThat(((SourceHttp.Result.Ok<String>) result).body()).isEqualTo(big);
    }

    @Test
    void returnsTheEtagAndHandles304() {
        wm.stubFor(get(urlEqualTo("/etag")).willReturn(okJson("{}").withHeader("ETag", "W/\"abc\"")));
        wm.stubFor(get(urlEqualTo("/etag")).withHeader("If-None-Match", containing("abc"))
                .willReturn(aResponse().withStatus(304)));

        var first = http.get(uri("/etag"), null, SourceHttpTest::asString);
        // Send back exactly what the server gave us. (WireMock's Jetty appends "--gzip" to the ETag of a
        // compressed response and strips it again from If-None-Match, so the stub sees "abc" either way.)
        String etag = ((SourceHttp.Result.Ok<String>) first).etag();
        var second = http.get(uri("/etag"), etag, SourceHttpTest::asString);

        assertThat(etag).startsWith("W/\"abc");
        assertThat(second).isInstanceOf(SourceHttp.Result.NotModified.class);
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        wm.stubFor(get(urlEqualTo("/flaky")).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("recovered"));
        wm.stubFor(get(urlEqualTo("/flaky")).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"ok\": true}")));

        var result = http.get(uri("/flaky"), null, SourceHttpTest::asString);

        assertThat(result).isInstanceOf(SourceHttp.Result.Ok.class);
        wm.verify(2, getRequestedFor(urlEqualTo("/flaky")));
    }

    @Test
    void givesUpAfterTwoRetries() {
        wm.stubFor(get(urlEqualTo("/down")).willReturn(serviceUnavailable()));

        assertThatThrownBy(() -> http.get(uri("/down"), null, SourceHttpTest::asString))
                .isInstanceOfSatisfying(SourceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.SERVER_ERROR);
                    assertThat(e.status()).isEqualTo(503);
                });
        wm.verify(3, getRequestedFor(urlEqualTo("/down")));
    }

    @Test
    void doesNotRetryNotFound() {
        wm.stubFor(get(urlEqualTo("/missing")).willReturn(notFound()));

        assertThatThrownBy(() -> http.get(uri("/missing"), null, SourceHttpTest::asString))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
        wm.verify(1, getRequestedFor(urlEqualTo("/missing")));
    }

    @Test
    void rateLimitingIsNotRetriedAndKeepsRetryAfter() {
        wm.stubFor(get(urlEqualTo("/slow-down"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));

        assertThatThrownBy(() -> http.get(uri("/slow-down"), null, SourceHttpTest::asString))
                .isInstanceOfSatisfying(SourceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(120));
                });
        wm.verify(1, getRequestedFor(urlEqualTo("/slow-down")));
    }

    @Test
    void aTimeoutIsANetworkErrorAndIsRetried() {
        wm.stubFor(get(urlEqualTo("/hang")).willReturn(okJson("{}").withFixedDelay(2_000)));

        assertThatThrownBy(() -> http.get(uri("/hang"), null, SourceHttpTest::asString))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NETWORK));
        wm.verify(3, getRequestedFor(urlEqualTo("/hang")));
    }

    @Test
    void invalidJsonIsABadResponseAndIsNotRetried() {
        wm.stubFor(get(urlEqualTo("/html")).willReturn(aResponse().withStatus(200).withBody("<html>oops</html>")));

        assertThatThrownBy(() -> http.get(uri("/html"), null, body -> TestHttp.MAPPER.readTree(body)))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
        wm.verify(1, getRequestedFor(urlEqualTo("/html")));
    }

    @Test
    void stopsReadingBodiesOverTheLimit() {
        var in = new SourceHttp.LimitedInputStream(new ByteArrayInputStream(new byte[11]), 10);

        assertThatThrownBy(in::readAllBytes).isInstanceOf(SourceHttp.BodyTooLargeException.class);
    }

    @Test
    void parsesRetryAfterInEitherFormat() {
        assertThat(SourceHttp.parseRetryAfter("30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(SourceHttp.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isEqualTo(Duration.ZERO); // past
        assertThat(SourceHttp.parseRetryAfter("soon")).isNull();
        assertThat(SourceHttp.parseRetryAfter(null)).isNull();
    }

    @Test
    void userAgentPointsAtTheRepoWhenRunningLocally() {
        assertThat(SourceHttp.userAgent("http://localhost:8080")).isEqualTo(TestHttp.USER_AGENT);
        assertThat(SourceHttp.userAgent("https://earlybird.onrender.com"))
                .isEqualTo("EarlyBirdBot/1.0 (+https://earlybird.onrender.com)");
    }
}
