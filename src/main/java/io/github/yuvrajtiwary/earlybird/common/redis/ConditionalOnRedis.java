package io.github.yuvrajtiwary.earlybird.common.redis;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Only when {@code REDIS_URL} is set. Without it, everything uses the in-memory or Postgres fallbacks. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnRedis.RedisConfigured.class)
public @interface ConditionalOnRedis {

    final class RedisConfigured extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty("earlybird.redis-url", "");
            return url.isBlank()
                    ? ConditionOutcome.noMatch("REDIS_URL is empty")
                    : ConditionOutcome.match("REDIS_URL is set");
        }
    }
}
