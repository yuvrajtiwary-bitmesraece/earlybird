package io.github.yuvrajtiwary.earlybird.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole app on a random port against a real Postgres in Docker. Name these classes {@code *IT}: they run
 * in Maven's integration-test phase (failsafe). Without Docker (the laptop) they are skipped, not failed;
 * CI has Docker, so they always run there. All such classes share one Spring context and one container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {}
