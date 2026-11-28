package io.github.yuvrajtiwary.earlybird.alerts;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.NOTIFIER)
class AlertsConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertsConfig.class);

    @Bean
    DailyCaps dailyCaps(ObjectProvider<StringRedisTemplate> redis, ObjectProvider<RedisGuard> guard,
            AlertRepository alerts, Clock clock) {
        RedisGuard g = guard.getIfAvailable();
        return new DailyCaps(g == null ? null : redis.getObject(), g, alerts, clock);
    }

    /**
     * Logo cards ship in the jar under {@code static/logos/<domain>.png} and are served by Spring's static resource
     * handler, so Telegram can fetch them from PUBLIC_BASE_URL (issue #10).
     */
    @Bean
    AlertFormatter alertFormatter(EarlyBirdProperties properties) throws IOException {
        Set<String> domains = new HashSet<>();
        for (Resource card : new PathMatchingResourcePatternResolver().getResources("classpath:static/logos/*.png")) {
            String name = card.getFilename();
            if (name != null) {
                domains.add(name.substring(0, name.length() - ".png".length()));
            }
        }
        String base = properties.publicBaseUrl().toString().replaceAll("/+$", "") + "/logos/";
        log.info("Logo cards: {} companies", domains.size());
        return new AlertFormatter(properties.alerts().prepLinkTemplate(), base, domains);
    }

    @Bean
    Notifier notifier(AlertRepository alerts, UserRepository users, TelegramApi telegram, RateLimiter localRateLimiter,
            DailyCaps caps, AlertFormatter formatter, EventBus bus, Clock clock, MeterRegistry meters) {
        return new Notifier(alerts, users, telegram, localRateLimiter, caps, formatter, bus, clock, meters,
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(5)));
    }

    @Bean
    DigestService digestService(AlertRepository alerts, UserRepository users, TelegramApi telegram,
            RateLimiter localRateLimiter, AlertFormatter formatter, Clock clock) {
        return new DigestService(alerts, users, telegram, localRateLimiter, formatter, clock);
    }
}
