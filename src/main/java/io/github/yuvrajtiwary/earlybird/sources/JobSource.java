package io.github.yuvrajtiwary.earlybird.sources;

import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.util.List;

/** Reads one careers-board platform. One implementation per platform. */
public interface JobSource {

    Source source();

    /**
     * Fetches the board's currently open jobs.
     *
     * @param etag             the ETag from the last full response, or null. If the board hasn't changed, the
     *                         platform answers 304 and this returns {@link FetchResult.NotModified}.
     * @param withDescriptions ask for descriptions in the list even where that's expensive (Greenhouse
     *                         {@code content=true}); used once per board, on its first (baseline) check
     * @throws SourceException if the board couldn't be read
     */
    FetchResult fetch(String token, String etag, boolean withDescriptions);

    /** Fills in descriptions the list left out. Only Greenhouse needs this; the others list them already. */
    default List<SourceJob> describe(String token, List<SourceJob> jobs) {
        return jobs;
    }

    sealed interface FetchResult {

        record NotModified() implements FetchResult {}

        record Jobs(List<SourceJob> jobs, String etag) implements FetchResult {
            public Jobs {
                jobs = List.copyOf(jobs);
            }
        }
    }
}
