package io.github.yuvrajtiwary.earlybird.config;

import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Backs {@link ConditionalOnRole}. Extends SpringBootCondition so the outcome shows in the conditions report. */
final class OnRoleCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Role[] wanted = metadata.getAnnotations().get(ConditionalOnRole.class).getEnumArray("value", Role.class);
        Set<Role> enabled = Role.enabledIn(context.getEnvironment());
        for (Role role : wanted) {
            if (enabled.contains(role)) {
                return ConditionOutcome.match("role '" + role.id() + "' is enabled");
            }
        }
        return ConditionOutcome.noMatch("none of " + Arrays.toString(wanted) + " is in EARLYBIRD_ROLES " + enabled);
    }
}
