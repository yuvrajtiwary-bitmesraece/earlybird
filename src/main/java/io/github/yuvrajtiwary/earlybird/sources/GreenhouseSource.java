package io.github.yuvrajtiwary.earlybird.sources;

import static io.github.yuvrajtiwary.earlybird.sources.JsonStream.instant;
import static io.github.yuvrajtiwary.earlybird.sources.JsonStream.names;
import static io.github.yuvrajtiwary.earlybird.sources.JsonStream.text;

import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.sources.SourceException.Kind;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Greenhouse job boards: {@code GET /v1/boards/{token}/jobs}.
 *
 * <p>The list is light (no descriptions), so it is cheap to read every 3 minutes. Descriptions come from
 * {@code /jobs/{id}} for new jobs only, or from the list with {@code content=true} when many jobs are new
 * at once (a board's first check). Publish time: {@code first_published} is trustworthy; {@code updated_at}
 * changes on every edit, so it isn't used.
 */
public final class GreenhouseSource implements JobSource {

    public static final String DEFAULT_BASE_URL = "https://boards-api.greenhouse.io";

    /** More new jobs than this: one content=true list call is cheaper than one detail call per job. */
    static final int DETAIL_CALLS_LIMIT = 5;

    /**
     * Greenhouse wraps the company's board-wide boilerplate ("We are X, we value Y…") in these blocks. Dropping
     * them leaves the job itself within the 3,000-character budget, and stops identical boilerplate from
     * dominating the repost fingerprint.
     */
    static final String[] BOILERPLATE = {".content-intro", ".content-conclusion"};

    private final SourceHttp http;
    private final ObjectMapper mapper;
    private final URI baseUrl;

    public GreenhouseSource(SourceHttp http, ObjectMapper mapper, URI baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Source source() {
        return Source.GREENHOUSE;
    }

    @Override
    public FetchResult fetch(String token, String etag, boolean withDescriptions) {
        var uri = UriComponentsBuilder.fromUri(baseUrl).path("/v1/boards/{token}/jobs");
        if (withDescriptions) {
            uri.queryParam("content", "true");
        }
        SourceHttp.Result<List<SourceJob>> result = http.get(uri.buildAndExpand(token).encode().toUri(),
                withDescriptions ? null : etag, body -> {
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

    @Override
    public List<SourceJob> describe(String token, List<SourceJob> jobs) {
        if (jobs.size() > DETAIL_CALLS_LIMIT) {
            Map<String, SourceJob> withContent = new HashMap<>();
            if (fetch(token, null, true) instanceof FetchResult.Jobs(List<SourceJob> all, String ignored)) {
                all.forEach(j -> withContent.put(j.externalId(), j));
            }
            return jobs.stream()
                    .map(j -> {
                        SourceJob full = withContent.get(j.externalId());
                        return full == null ? j : j.withDetails(full.description(), full.departments());
                    })
                    .toList();
        }
        List<SourceJob> described = new ArrayList<>(jobs.size());
        for (SourceJob job : jobs) {
            described.add(detail(token, job));
        }
        return described;
    }

    private SourceJob detail(String token, SourceJob job) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl)
                .path("/v1/boards/{token}/jobs/{id}")
                .buildAndExpand(token, job.externalId())
                .encode()
                .toUri();
        try {
            SourceHttp.Result<JsonNode> result = http.get(uri, null, body -> mapper.readTree(body));
            if (result instanceof SourceHttp.Result.Ok<JsonNode>(JsonNode node, String ignored)) {
                return job.withDetails(HtmlText.fromEscapedHtml(text(node, "content"), BOILERPLATE),
                        names(node, "departments"));
            }
            return job;
        } catch (SourceException e) {
            if (e.kind() == Kind.NOT_FOUND) {
                return job; // removed between the list call and this one; it will close on a later check
            }
            throw e;
        }
    }

    private static void addIfValid(List<SourceJob> jobs, JsonNode job) {
        String id = text(job, "id");
        String url = text(job, "absolute_url");
        if (id == null || url == null) {
            return;
        }
        String content = text(job, "content");
        jobs.add(new SourceJob(
                id,
                text(job, "title"),
                url,
                text(job.path("location"), "name"),
                names(job, "departments"),
                instant(text(job, "first_published")),
                content == null ? null : HtmlText.fromEscapedHtml(content, BOILERPLATE)));
    }
}
