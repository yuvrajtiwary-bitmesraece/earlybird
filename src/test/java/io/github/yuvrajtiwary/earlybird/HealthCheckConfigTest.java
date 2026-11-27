package io.github.yuvrajtiwary.earlybird;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * Issue #8: Render restarts an instance whose health check fails, so the path it checks must not depend on the
 * database. A busy connection pool once got a healthy app restarted twice.
 */
class HealthCheckConfigTest {

    @Test
    void renderChecksLivenessAndLivenessNeverTouchesTheDatabase() throws Exception {
        String blueprint = Files.readString(Path.of("render.yaml"));
        assertThat(blueprint).containsPattern("(?m)^\s*healthCheckPath: /actuator/health/liveness\s*$");

        var app = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).getFirst();
        String liveness = String.valueOf(app.getProperty("management.endpoint.health.group.liveness.include"));
        String readiness = String.valueOf(app.getProperty("management.endpoint.health.group.readiness.include"));

        assertThat(liveness).isEqualTo("livenessState");
        assertThat(readiness).as("readiness keeps the database for platforms that probe it separately").contains("db");
    }
}
