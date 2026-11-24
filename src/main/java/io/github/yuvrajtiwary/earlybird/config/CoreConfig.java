package io.github.yuvrajtiwary.earlybird.config;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.common.TokenBucketRateLimiter;
import io.github.yuvrajtiwary.earlybird.ops.DistributedLock;
import io.github.yuvrajtiwary.earlybird.ops.InMemoryDistributedLock;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Beans every role needs. The lock and rate limiter are in-memory for now; the Redis-backed versions
 * (with these as the fallback when Redis is unreachable) arrive in later blocks.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EarlyBirdProperties.class)
class CoreConfig {

    /** One clock for the whole app, always UTC, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DistributedLock distributedLock(Clock clock) {
        return new InMemoryDistributedLock(clock);
    }

    /** The default limiter: in-process (per-user and per-chat limits). See RedisConfig for the shared one. */
    @Bean
    @Primary
    RateLimiter localRateLimiter(Clock clock) {
        return new TokenBucketRateLimiter(clock);
    }
}
