package io.github.yuvrajtiwary.earlybird.config;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The modules one EarlyBird process can run. {@code EARLYBIRD_ROLES} picks them: on Render every role runs
 * in one container; in the Helm chart each group of roles is its own Deployment, with no code change.
 */
public enum Role {
    WEB, BOT, POLLER, ENRICHER, MATCHER, NOTIFIER, SCHEDULER;

    /** Lowercase name, as written in {@code EARLYBIRD_ROLES}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Roles enabled in this environment. Read straight from the {@link Environment} (not from the
     * properties bean) because bean conditions are evaluated before any bean exists. Unset means all roles.
     */
    public static Set<Role> enabledIn(Environment environment) {
        return Binder.get(environment)
                .bind("earlybird.roles", Bindable.setOf(Role.class))
                .orElseGet(() -> EnumSet.allOf(Role.class));
    }
}
