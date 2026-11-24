package io.github.yuvrajtiwary.earlybird.sources;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.sources.JobSource.FetchResult;
import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Against real Ashby responses (PostHog, Atlan) saved as fixtures. */
class AshbySourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)) // keep ETags exactly as sent (see SourceHttpTest)
            .build();

    private AshbySource source;

    @BeforeEach
    void setUp() {
        source = new AshbySource(TestHttp.fast(), TestHttp.MAPPER, URI.create(wm.baseUrl()));
    }

    private List<SourceJob> fetch(String board, String body) {
        wm.stubFor(get(urlPathEqualTo("/posting-api/job-board/" + board)).willReturn(okJson(body)));
        return ((FetchResult.Jobs) source.fetch(board, null, false)).jobs();
    }

    @Test
    void parsesJobsAndTrimsTitles() {
        List<SourceJob> jobs = fetch("posthog", Fixtures.read("ashby/posthog-job-board.json"));

        assertThat(jobs).hasSize(3);
        SourceJob first = jobs.getFirst();
        assertThat(first.externalId()).isEqualTo("db808740-242e-4a40-ad43-d41f26c049d7");
        assertThat(first.title()).isEqualTo("Technical Support Engineer"); // API: " Technical Support Engineer"
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-11-06T15:25:28.875Z"));
        assertThat(first.departments()).containsExactly("Support Engineering"); // department == team, deduped
        assertThat(first.description()).isNotBlank();
    }

    @Test
    void workModeIsAddedOnlyWhenTheLocationDoesntSayIt() {
        List<SourceJob> posthog = fetch("posthog", Fixtures.read("ashby/posthog-job-board.json"));
        assertThat(posthog).extracting(SourceJob::locationRaw)
                .containsExactly("Remote", "Remote (EMEA)", "Hybrid (UK)");

        List<SourceJob> atlan = fetch("atlan", Fixtures.read("ashby/atlan-job-board.json"));
        assertThat(atlan).extracting(SourceJob::locationRaw)
                .containsExactly("India · Remote", "San Francisco · Remote", "United States");
    }

    @Test
    void unlistedJobsAreSkipped() {
        String body = """
                {"apiVersion": "1", "jobs": [
                  {"id": "a", "title": "Listed", "jobUrl": "https://jobs.ashbyhq.com/x/a", "isListed": true},
                  {"id": "b", "title": "Hidden", "jobUrl": "https://jobs.ashbyhq.com/x/b", "isListed": false}
                ]}""";

        assertThat(fetch("x", body)).extracting(SourceJob::externalId).containsExactly("a");
    }

    @Test
    void jobsWithoutAnIdOrUrlAreSkipped() {
        String body = """
                {"jobs": [{"title": "No id", "jobUrl": "https://x"}, {"id": "c", "title": "No url"}]}""";

        assertThat(fetch("broken", body)).isEmpty();
    }
}
