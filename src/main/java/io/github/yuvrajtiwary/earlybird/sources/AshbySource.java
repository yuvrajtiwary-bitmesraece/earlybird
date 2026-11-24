package io.github.yuvrajtiwary.earlybird.sources;

import static io.github.yuvrajtiwary.earlybird.sources.JsonStream.instant;
import static io.github.yuvrajtiwary.earlybird.sources.JsonStream.text;

import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ashby job boards: {@code GET /posting-api/job-board/{name}}, descriptions included.
 *
 * <p>Publish time: {@code publishedAt} is trustworthy. Unlisted jobs ({@code isListed: false}) are skipped.
 * Titles sometimes carry stray spaces, which {@link SourceJob} trims.
 */
public final class AshbySource implements JobSource {

    public static final String DEFAULT_BASE_URL = "https://api.ashbyhq.com";

    private final SourceHttp http;
    private final ObjectMapper mapper;
    private final URI baseUrl;

    public AshbySource(SourceHttp http, ObjectMapper mapper, URI baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Source source() {
        return Source.ASHBY;
    }

    @Override
    public FetchResult fetch(String token, String etag, boolean withDescriptions) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl)
                .path("/posting-api/job-board/{name}")
                .buildAndExpand(token)
                .encode()
                .toUri();
        SourceHttp.Result<List<SourceJob>> result = http.get(uri, etag, body -> {
            List<SourceJob> jobs = new ArrayList<>();
            JsonStream.forEachInArray(mapper, body, "jobs", job -> addIfValid(jobs, job));
            return jobs;
        });
        return switch (result) {
            case SourceHttp.Result.NotModified<List<SourceJob>> ignored -> new FetchResult.NotModified();
            case SourceHttp.Result.Ok<List<SourceJob>>(List<SourceJob> jobs, String newEtag) ->
                    new FetchResult.Jobs(jobs, newEtag);
        };
    }

    private static void addIfValid(List<SourceJob> jobs, JsonNode job) {
        String id = text(job, "id");
        String url = text(job, "jobUrl");
        if (id == null || url == null || !job.path("isListed").asBoolean(true)) {
            return;
        }
        List<String> places = new ArrayList<>();
        places.add(text(job, "location"));
        for (JsonNode secondary : job.path("secondaryLocations")) {
            places.add(text(secondary, "location"));
        }
        String workplace = text(job, "workplaceType");
        if (workplace == null && job.path("isRemote").asBoolean(false)) {
            workplace = "Remote";
        }
        String plain = text(job, "descriptionPlain");
        jobs.add(new SourceJob(
                id,
                text(job, "title"),
                url,
                SourceJob.formatLocation(places, null, workplace),
                Arrays.asList(text(job, "department"), text(job, "team")), // nulls are dropped by SourceJob
                instant(text(job, "publishedAt")),
                plain != null ? HtmlText.normalize(plain) : HtmlText.toPlainText(text(job, "descriptionHtml"))));
    }
}
