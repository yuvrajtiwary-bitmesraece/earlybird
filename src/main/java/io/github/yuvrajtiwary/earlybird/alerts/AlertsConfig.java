package io.github.yuvrajtiwary.earlybird.alerts;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.ops.AdminNotifier;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.NOTIFIER)
class AlertsConfig {

    @Bean
    DailyCaps dailyCaps(ObjectProvider<StringRedisTemplate> redis, ObjectProvider<RedisGuard> guard,
            AlertRepository alerts, Clock clock) {
        RedisGuard g = guard.getIfAvailable();
        return new DailyCaps(g == null ? null : redis.getObject(), g, alerts, clock);
    }

    @Bean
    AlertFormatter alertFormatter(EarlyBirdProperties properties) {
        return new AlertFormatter(properties.alerts().prepLinkTemplate());
    }

    @Bean
    Notifier notifier(AlertRepository alerts, UserRepository users, TelegramApi telegram, RateLimiter localRateLimiter,
            DailyCaps caps, AlertFormatter formatter, EventBus bus, AdminNotifier admin, Clock clock, MeterRegistry meters) {
        return new Notifier(alerts, users, telegram, localRateLimiter, caps, formatter, bus, admin, clock, meters,
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(5)));
    }

    @Bean
    DigestService digestService(AlertRepository alerts, UserRepository users, TelegramApi telegram,
            RateLimiter localRateLimiter, AlertFormatter formatter, Clock clock) {
        return new DigestService(alerts, users, telegram, localRateLimiter, formatter, clock);
    }
}
