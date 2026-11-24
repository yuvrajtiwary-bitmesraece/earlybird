package io.github.yuvrajtiwary.earlybird.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers the annotated configuration class or bean only if at least one of the given roles is
 * enabled in {@code EARLYBIRD_ROLES}. Example: {@code @ConditionalOnRole(Role.POLLER)}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnRoleCondition.class)
public @interface ConditionalOnRole {

    /** Matches if any of these roles is enabled. */
    Role[] value();
}
