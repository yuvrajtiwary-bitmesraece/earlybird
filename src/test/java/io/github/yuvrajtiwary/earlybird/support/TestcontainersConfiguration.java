package io.github.yuvrajtiwary.earlybird.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared infrastructure for integration tests, one instance per Spring context:
 * <ul>
 *   <li>a throwaway Postgres (pgvector image = Supabase's Postgres 17); {@code @ServiceConnection} points the
 *       DataSource at it;
 *   <li>a WireMock server standing in for Greenhouse, Lever and Ashby, so tests never call real job boards.
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    }

    @Bean(destroyMethod = "stop")
    WireMockServer fakeJobBoards() {
        // No gzip: WireMock's Jetty would append "--gzip" to ETags, which real boards don't do.
        var server = new WireMockServer(options().dynamicPort().gzipDisabled(true));
        server.start();
        return server;
    }

    /** Every external service points at the one WireMock; tests stub what they need by path. */
    @Bean
    DynamicPropertyRegistrar externalServiceUrls(WireMockServer fakeJobBoards) {
        return registry -> {
            registry.add("earlybird.sources.greenhouse-base-url", fakeJobBoards::baseUrl);
            registry.add("earlybird.sources.lever-base-url", fakeJobBoards::baseUrl);
            registry.add("earlybird.sources.ashby-base-url", fakeJobBoards::baseUrl);
            registry.add("earlybird.telegram.api-base-url", fakeJobBoards::baseUrl);
            registry.add("earlybird.gemini.base-url", fakeJobBoards::baseUrl);
        };
    }
}
