package io.github.yuvrajtiwary.earlybird.sources;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.sources.SeedBoards.SeedBoard;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.ObjectMapper;

/** Job-source beans for the poller role, and loading the seed boards at startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.POLLER)
class SourcesConfig {

    private static final Logger log = LoggerFactory.getLogger(SourcesConfig.class);

    @Bean
    SourceHttp sourceHttp(EarlyBirdProperties properties) {
        return new SourceHttp(SourceHttp.userAgent(properties.publicBaseUrl().toString()));
    }

    @Bean
    JobSources jobSources(SourceHttp http, ObjectMapper mapper, EarlyBirdProperties properties) {
        var urls = properties.sources();
        return JobSources.create(http, mapper, urls.greenhouseBaseUrl(), urls.leverBaseUrl(), urls.ashbyBaseUrl());
    }

    /** Adds any seed board not yet in the database. Existing boards are left alone (insert-if-absent). */
    @Bean
    ApplicationRunner seedBoardLoader(EarlyBirdProperties properties, ResourceLoader resources,
            BoardRepository boards) {
        return args -> {
            String location = properties.sources().seedFile();
            if (location == null || location.isBlank()) {
                log.info("Seed boards: skipped (no seed file configured)");
                return;
            }
            Resource resource = resources.getResource(location);
            List<SeedBoard> seeds;
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                seeds = SeedBoards.parse(reader);
            }
            int added = 0;
            int domains = 0;
            for (SeedBoard seed : seeds) {
                if (boards.insertIfAbsent(seed.source(), seed.token(), seed.company())) {
                    added++;
                }
                if (seed.domain() != null && boards.updateDomain(seed.source(), seed.token(), seed.domain())) {
                    domains++;
                }
            }
            log.info("Seed boards: {} in {}, {} newly added, {} domains set", seeds.size(), location, added, domains);
        };
    }
}
