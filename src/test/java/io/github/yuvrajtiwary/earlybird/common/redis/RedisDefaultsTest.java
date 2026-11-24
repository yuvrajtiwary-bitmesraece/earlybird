package io.github.yuvrajtiwary.earlybird.common.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Regression: without REDIS_URL the whole app failed to start ("Invalid Redis URL ''"), because the
 * application.yml default was an empty string. Runs the real application.yml through Boot's Redis
 * auto-configuration with no REDIS_URL set.
 */
class RedisDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("application.yml", new ClassPathResource("application.yml"))
                            .forEach(context.getEnvironment().getPropertySources()::addLast);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            })
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class));

    @Test
    void theAppStartsWithoutRedisAndNeverDialsThePlaceholder() {
        runner.run(context -> {
            // Started means Boot accepted the URL and Lettuce did not try to connect (nothing listens here).
            assertThat(context).hasNotFailed().hasSingleBean(StringRedisTemplate.class);
            // Redis use is decided by this property alone, so the placeholder above stays unused.
            assertThat(context.getEnvironment().getProperty("earlybird.redis-url")).isNullOrEmpty();
        });
    }
}
