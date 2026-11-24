package io.github.yuvrajtiwary.earlybird.sources;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.sources.JobSource.FetchResult;
import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Against real Lever responses (CRED, Zeta) saved as fixtures. */
class LeverSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)) // keep ETags exactly as sent (see SourceHttpTest)
            .build();

    private LeverSource source;

    @BeforeEach
    void setUp() {
        source = new LeverSource(TestHttp.fast(), TestHttp.MAPPER, URI.create(wm.baseUrl()));
    }

    private List<SourceJob> fetch(String site, String fixture) {
        wm.stubFor(get(urlPathEqualTo("/v0/postings/" + site)).withQueryParam("mode", equalTo("json"))
                .willReturn(okJson(Fixtures.read(fixture))));
        return ((FetchResult.Jobs) source.fetch(site, null, false)).jobs();
    }

    @Test
    void parsesPostings() {
        List<SourceJob> jobs = fetch("cred", "lever/cred-postings.json");

        assertThat(jobs).hasSize(3);
        SourceJob first = jobs.getFirst();
        assertThat(first.externalId()).isEqualTo("af4cc539-ea8b-4405-8067-2d7d729c9759");
        assertThat(first.title()).isEqualTo("area collections manager chennai- flows");
        assertThat(first.url()).isEqualTo("https://jobs.lever.co/cred/af4cc539-ea8b-4405-8067-2d7d729c9759");
        assertThat(first.departments()).containsExactly("Prefr", "collections");
    }

    @Test
    void locationCombinesPlacesCountryAndWorkMode() {
        List<SourceJob> cred = fetch("cred", "lever/cred-postings.json");
        assertThat(cred.getFirst().locationRaw()).isEqualTo("tamil nadu, IN · On-site");

        List<SourceJob> zeta = fetch("zeta", "lever/zeta-postings.json");
        assertThat(zeta.get(0).locationRaw()).isEqualTo("Mumbai / Bangalore, IN · On-site");
        assertThat(zeta.get(2).locationRaw()).isEqualTo("East Coast, US · Remote");
    }

    @Test
    void hasNoPublishTimeBecauseCreatedAtIsNotOne() {
        assertThat(fetch("cred", "lever/cred-postings.json")).allSatisfy(j -> assertThat(j.publishedAt()).isNull());
    }

    @Test
    void requirementListsComeFirstSoTheyAreNotCutOff() {
        SourceJob devSecOps = fetch("zeta", "lever/zeta-postings.json").get(1);

        // Zeta's description opens with ~3,000 characters of "About us"; the requirements must survive the cut.
        assertThat(devSecOps.title()).isEqualTo("DevSecOps Engineer II");
        assertThat(devSecOps.description()).startsWith("Responsibilities").contains("Skills")
                .doesNotContain("<li>", "<ul>");
        assertThat(devSecOps.description().length()).isLessThanOrEqualTo(SourceJob.MAX_DESCRIPTION);
    }
}
