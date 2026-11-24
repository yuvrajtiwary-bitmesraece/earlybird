package io.github.yuvrajtiwary.earlybird.sources;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.sources.BoardValidator.Report;
import io.github.yuvrajtiwary.earlybird.sources.BoardValidator.Status;
import io.github.yuvrajtiwary.earlybird.sources.SeedBoards.SeedBoard;
import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class BoardValidatorTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Test
    void reportsEachBoardsStatus() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/groww/jobs")).willReturn(okJson(Fixtures.read("greenhouse/groww-jobs.json"))));
        wm.stubFor(get(urlPathEqualTo("/posting-api/job-board/quiet")).willReturn(okJson("{\"jobs\": []}")));
        wm.stubFor(get(urlPathEqualTo("/v0/postings/gone")).withQueryParam("mode", equalTo("json")).willReturn(notFound()));
        wm.stubFor(get(urlPathEqualTo("/v1/boards/broken/jobs")).willReturn(serverError()));
        URI base = URI.create(wm.baseUrl());
        var validator = new BoardValidator(JobSources.create(TestHttp.fast(), TestHttp.MAPPER, base, base, base));

        List<Report> reports = validator.validate(List.of(
                new SeedBoard(Source.GREENHOUSE, "groww", "Groww"),
                new SeedBoard(Source.ASHBY, "quiet", "Quiet"),
                new SeedBoard(Source.LEVER, "gone", "Gone"),
                new SeedBoard(Source.GREENHOUSE, "broken", "Broken")));

        assertThat(reports).extracting(Report::status)
                .containsExactly(Status.OK, Status.EMPTY, Status.NOT_FOUND, Status.ERROR);
        assertThat(reports.get(0).jobs()).isEqualTo(3);
        assertThat(reports.get(0).indiaOrRemoteJobs()).isEqualTo(3); // all three are in Bengaluru
        assertThat(reports).extracting(Report::validToken).containsExactly(true, true, false, false);
    }
}
