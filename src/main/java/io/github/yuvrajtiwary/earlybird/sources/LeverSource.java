package io.github.yuvrajtiwary.earlybird.sources;

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
 * Lever postings: {@code GET /v0/postings/{site}?mode=json}, descriptions included.
 *
 * <p>Lever has no publish time. {@code createdAt} is when the posting was created in Lever, which can be
 * months before it went live, so {@code publishedAt} stays null for Lever jobs. Requirements usually sit in
 * {@code lists}, separate from {@code descriptionPlain}, so both are kept. Lever doesn't gzip its responses.
 */
public final class LeverSource implements JobSource {

    public static final String DEFAULT_BASE_URL = "https://api.lever.co";

    private final SourceHttp http;
    private final ObjectMapper mapper;
    private final URI baseUrl;

    public LeverSource(SourceHttp http, ObjectMapper mapper, URI baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Source source() {
        return Source.LEVER;
    }

    @Override
    public FetchResult fetch(String token, String etag, boolean withDescriptions) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl)
                .path("/v0/postings/{site}")
                .queryParam("mode", "json")
                .buildAndExpand(token)
                .encode()
                .toUri();
        SourceHttp.Result<List<SourceJob>> result = http.get(uri, etag, body -> {
            List<SourceJob> jobs = new ArrayList<>();
            JsonStream.forEachInArray(mapper, body, null, posting -> addIfValid(jobs, posting));
            return jobs;
        });
        return switch (result) {
            case SourceHttp.Result.NotModified<List<SourceJob>> ignored -> new FetchResult.NotModified();
            case SourceHttp.Result.Ok<List<SourceJob>>(List<SourceJob> jobs, String newEtag) ->
                    new FetchResult.Jobs(jobs, newEtag);
        };
    }

    private static void addIfValid(List<SourceJob> jobs, JsonNode posting) {
        String id = text(posting, "id");
        String url = text(posting, "hostedUrl");
        if (id == null || url == null) {
            return;
        }
        JsonNode categories = posting.path("categories");
        List<String> places = new ArrayList<>();
        for (JsonNode place : categories.path("allLocations")) {
            places.add(place.asString());
        }
        if (places.isEmpty()) {
            places.add(text(categories, "location"));
        }
        jobs.add(new SourceJob(
                id,
                text(posting, "text"),
                url,
                SourceJob.formatLocation(places, text(posting, "country"), text(posting, "workplaceType")),
                Arrays.asList(text(categories, "department"), text(categories, "team")), // nulls dropped by SourceJob
                null,
                description(posting)));
    }

    /**
     * Requirement lists first, then the general description. Many companies open {@code descriptionPlain} with
     * paragraphs of company boilerplate; in the original order the 3,000-character cut can remove the
     * requirements entirely, and those are what skill extraction needs. The text is only for tagging and the
     * fingerprint (never shown to users), so the order is ours to choose.
     */
    private static String description(JsonNode posting) {
        StringBuilder text = new StringBuilder();
        for (JsonNode list : posting.path("lists")) {
            text.append(nullToEmpty(text(list, "text"))).append('\n')
                    .append(HtmlText.toPlainText(text(list, "content"))).append("\n\n");
        }
        text.append(nullToEmpty(text(posting, "descriptionPlain")))
                .append("\n\n").append(nullToEmpty(text(posting, "additionalPlain")));
        return HtmlText.normalize(text.toString());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
