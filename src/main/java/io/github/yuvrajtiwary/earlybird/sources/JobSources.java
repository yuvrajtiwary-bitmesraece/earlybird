package io.github.yuvrajtiwary.earlybird.sources;

import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** The {@link JobSource} for each platform. */
public final class JobSources {

    private final Map<Source, JobSource> bySource = new EnumMap<>(Source.class);

    public JobSources(List<JobSource> sources) {
        sources.forEach(s -> bySource.put(s.source(), s));
        for (Source s : Source.values()) {
            if (!bySource.containsKey(s)) {
                throw new IllegalArgumentException("no JobSource for " + s);
            }
        }
    }

    public static JobSources create(SourceHttp http, ObjectMapper mapper, URI greenhouse, URI lever, URI ashby) {
        return new JobSources(List.of(
                new GreenhouseSource(http, mapper, greenhouse),
                new LeverSource(http, mapper, lever),
                new AshbySource(http, mapper, ashby)));
    }

    public JobSource get(Source source) {
        return bySource.get(source);
    }
}
