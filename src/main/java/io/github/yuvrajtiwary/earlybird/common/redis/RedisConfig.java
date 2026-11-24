package io.github.yuvrajtiwary.earlybird.common.redis;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed pieces exist only when {@code REDIS_URL} is set. {@code sharedRateLimiter} is the limit that must
 * hold across processes (Telegram's global send rate); per-user and per-chat limits stay local, because a
 * user's traffic is always handled by one process.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    public static final String SHARED_RATE_LIMITER = "sharedRateLimiter";

    @Bean
    @ConditionalOnRedis
    RedisGuard redisGuard(EarlyBirdProperties properties, Clock clock, MeterRegistry meters) {
        return new RedisGuard(properties.env(), clock, meters);
    }

    @Bean(SHARED_RATE_LIMITER)
    RateLimiter sharedRateLimiter(ObjectProvider<RedisGuard> guard, ObjectProvider<StringRedisTemplate> redis,
            RateLimiter localRateLimiter, Clock clock) {
        RedisGuard g = guard.getIfAvailable();
        return g == null ? localRateLimiter : new RedisRateLimiter(redis.getObject(), g, localRateLimiter, clock);
    }
}
