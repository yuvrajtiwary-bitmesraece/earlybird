package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.ops.AdminNotifier;
import io.github.yuvrajtiwary.earlybird.persistence.GeminiUsageRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTagsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Gemini, the skill vocabulary, and (enricher role) job tagging. */
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

    @Bean
    HiringRanking hiringRanking(ObjectProvider<StringRedisTemplate> redis, ObjectProvider<RedisGuard> guard,
            Clock clock) {
        RedisGuard g = guard.getIfAvailable();
        return new HiringRanking(g == null ? null : redis.getObject(), g, clock);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRole(Role.ENRICHER)
    static class Tagging {

        @Bean
        TaggingWorker taggingWorker(PostingRepository postings, PostingTagsRepository tags,
                TransactionTemplate transaction, GeminiClient gemini, SkillVocabulary vocabulary, ObjectMapper mapper,
                EventBus bus, HiringRanking hiring, AdminNotifier admin, Clock clock, MeterRegistry meters,
                @Value("${earlybird.tagging.background:true}") boolean background) {
            return new TaggingWorker(postings, tags, transaction, new JobTagger(gemini, vocabulary, mapper, clock),
                    new FallbackTagger(vocabulary), bus, hiring, admin, clock, meters, background);
        }
    }
}
