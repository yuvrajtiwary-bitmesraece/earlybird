package io.github.yuvrajtiwary.earlybird.sources;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.sources.JobSource.FetchResult;
import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Against real Greenhouse responses saved as fixtures, served by an in-process WireMock. */
class GreenhouseSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)) // keep ETags exactly as sent (see SourceHttpTest)
            .build();

    private GreenhouseSource source;

    @BeforeEach
    void setUp() {
        source = new GreenhouseSource(TestHttp.fast(), TestHttp.MAPPER, URI.create(wm.baseUrl()));
    }

    private static List<SourceJob> jobs(FetchResult result) {
        assertThat(result).isInstanceOf(FetchResult.Jobs.class);
        return ((FetchResult.Jobs) result).jobs();
    }

    @Test
    void parsesTheJobList() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/hackerrank/jobs"))
                .willReturn(okJson(Fixtures.read("greenhouse/hackerrank-jobs.json")).withHeader("ETag", "W/\"v1\"")));

        FetchResult result = source.fetch("hackerrank", null, false);

        List<SourceJob> jobs = jobs(result);
        assertThat(jobs).hasSize(3);
        SourceJob first = jobs.getFirst();
        assertThat(first.externalId()).isEqualTo("8394096");
        assertThat(first.title()).isEqualTo("Account Executive - Enterprise - East");
        assertThat(first.url()).isEqualTo("https://job-boards.greenhouse.io/hackerrank/jobs/8394096");
        assertThat(first.locationRaw())
                .isEqualTo("Remote in New York, New Jersey, Boston, Washington D.C., Philadelphia");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-11-16T21:08:54Z")); // -05:00 converted
        assertThat(first.description()).isNull(); // the light list has no content
        assertThat(((FetchResult.Jobs) result).etag()).isEqualTo("W/\"v1\"");
    }

    @Test
    void trimsTrailingSpacesInTitles() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/groww/jobs"))
                .willReturn(okJson(Fixtures.read("greenhouse/groww-jobs.json"))));

        assertThat(jobs(source.fetch("groww", null, false)))
                .extracting(SourceJob::title)
                .contains("Creative Illustrator"); // the API says "Creative Illustrator "
    }

    @Test
    void sendsTheEtagAndUnderstandsNotModified() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/hackerrank/jobs"))
                .withHeader("If-None-Match", equalTo("W/\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        assertThat(source.fetch("hackerrank", "W/\"v1\"", false)).isInstanceOf(FetchResult.NotModified.class);
    }

    @Test
    void theBaselineCheckAsksForContentAndIgnoresTheEtag() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/groww/jobs"))
                .withQueryParam("content", equalTo("true"))
                .willReturn(okJson(Fixtures.read("greenhouse/groww-jobs-content.json"))));

        List<SourceJob> jobs = jobs(source.fetch("groww", "W/\"stale\"", true));

        assertThat(jobs).hasSize(2);
        assertThat(jobs.getFirst().departments()).containsExactly("Internal Controls");
        assertThat(jobs).allSatisfy(j -> assertThat(j.description()).isNotBlank().doesNotContain("<", "&lt;"));
        wm.verify(getRequestedFor(urlPathEqualTo("/v1/boards/groww/jobs")).withHeader("If-None-Match", absent()));
    }

    @Test
    void describesAFewNewJobsWithOneDetailCallEach() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/hackerrank/jobs/8394096"))
                .willReturn(okJson(Fixtures.read("greenhouse/hackerrank-job-detail.json"))));
        var job = new SourceJob("8394096", "Account Executive", "https://x", null, List.of(), null, null);

        SourceJob described = source.describe("hackerrank", List.of(job)).getFirst();

        // The board-wide intro ("HackerRank helps companies like NVIDIA...") is dropped; the job itself starts.
        assertThat(described.description()).startsWith("About the team")
                .doesNotContain("helps companies like", "Want to learn more about HackerRank")
                .doesNotContain("<div", "&lt;", "&quot;");
        assertThat(described.description().length()).isLessThanOrEqualTo(SourceJob.MAX_DESCRIPTION);
        assertThat(described.departments()).containsExactly("Go To Market");
    }

    @Test
    void describesManyNewJobsWithOneListCall() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/groww/jobs"))
                .withQueryParam("content", equalTo("true"))
                .willReturn(okJson(Fixtures.read("greenhouse/groww-jobs-content.json"))));
        List<SourceJob> fresh = new ArrayList<>();
        for (String id : List.of("4970739101", "4979964101", "1", "2", "3", "4")) { // > DETAIL_CALLS_LIMIT
            fresh.add(new SourceJob(id, "t", "https://x", null, List.of(), null, null));
        }

        List<SourceJob> described = source.describe("groww", fresh);

        assertThat(described.get(0).description()).isNotBlank();
        assertThat(described.get(1).description()).isNotBlank();
        assertThat(described.get(2).description()).isNull(); // not in the list any more
        wm.verify(0, getRequestedFor(urlPathMatching("/v1/boards/groww/jobs/.*")));
    }

    @Test
    void aJobRemovedBeforeItsDetailCallIsKeptWithoutADescription() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/hackerrank/jobs/999")).willReturn(notFound()));
        var job = new SourceJob("999", "Gone", "https://x", null, List.of(), null, null);

        assertThat(source.describe("hackerrank", List.of(job)).getFirst().description()).isNull();
    }

    @Test
    void anUnknownBoardIsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/nope/jobs")).willReturn(notFound()));

        assertThatThrownBy(() -> source.fetch("nope", null, false))
                .isInstanceOfSatisfying(SourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(SourceException.Kind.NOT_FOUND));
    }
}
