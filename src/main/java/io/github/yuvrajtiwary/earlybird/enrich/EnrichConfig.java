package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.persistence.GeminiUsageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Gemini and the skill vocabulary. Resume parsing (bot) and job tagging (enricher) both use them. */
@Configuration(proxyBeanMethods = false)
class EnrichConfig {

    @Bean
    SkillVocabulary skillVocabulary() {
        return SkillVocabulary.fromClasspath();
    }

    @Bean
    @ConditionalOnRole({Role.BOT, Role.ENRICHER, Role.WEB})
    GeminiBudget geminiBudget(GeminiUsageRepository usage, Clock clock, EarlyBirdProperties properties) {
        return new GeminiBudget(usage, clock, properties.gemini().maxCallsPerDay());
    }

    @Bean
    @ConditionalOnRole({Role.BOT, Role.ENRICHER})
    GeminiClient geminiClient(ObjectMapper mapper, GeminiBudget budget, MeterRegistry meters,
            EarlyBirdProperties properties) {
        var gemini = properties.gemini();
        return new GeminiClient(mapper, budget, meters, gemini.baseUrl(), gemini.apiKey(), gemini.model());
    }
}
