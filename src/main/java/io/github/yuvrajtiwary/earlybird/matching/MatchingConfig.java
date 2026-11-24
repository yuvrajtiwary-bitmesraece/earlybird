package io.github.yuvrajtiwary.earlybird.matching;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.MatchRepository;
import io.github.yuvrajtiwary.earlybird.persistence.ProcessedEventRepository;
import io.github.yuvrajtiwary.earlybird.persistence.ProfileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.MATCHER)
class MatchingConfig {

    @Bean
    Matcher matcher(MatchRepository matches, AlertRepository alerts, ProfileRepository profiles,
            ProcessedEventRepository processed, EventBus bus, TransactionTemplate transaction, Clock clock,
            MeterRegistry meters) {
        return new Matcher(matches, alerts, profiles, processed, bus, transaction, clock, meters);
    }
}
