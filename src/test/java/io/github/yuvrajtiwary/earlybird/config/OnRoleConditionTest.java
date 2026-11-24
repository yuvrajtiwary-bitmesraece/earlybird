package io.github.yuvrajtiwary.earlybird.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OnRoleConditionTest {

    record PollerMarker() {}

    record WebOrBotMarker() {}

    /** The annotation on a whole configuration class. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRole(Role.POLLER)
    static class PollerConfig {
        @Bean
        PollerMarker pollerMarker() {
            return new PollerMarker();
        }
    }

    /** The annotation on a single bean, with any-of semantics. */
    @Configuration(proxyBeanMethods = false)
    static class WebOrBotConfig {
        @Bean
        @ConditionalOnRole({Role.WEB, Role.BOT})
        WebOrBotMarker webOrBotMarker() {
            return new WebOrBotMarker();
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PollerConfig.class, WebOrBotConfig.class);

    @Test
    void allRolesRunWhenNoneAreConfigured() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(PollerMarker.class).hasSingleBean(WebOrBotMarker.class));
    }

    @Test
    void onlyTheListedRolesRun() {
        runner.withPropertyValues("earlybird.roles=web")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PollerMarker.class).hasSingleBean(WebOrBotMarker.class));
    }

    @Test
    void anyOneOfSeveralRolesIsEnough() {
        runner.withPropertyValues("earlybird.roles=bot")
                .run(ctx -> assertThat(ctx).hasSingleBean(WebOrBotMarker.class));
    }

    @Test
    void beansForRolesThatAreOffAreSkipped() {
        runner.withPropertyValues("earlybird.roles=poller,scheduler")
                .run(ctx -> assertThat(ctx).hasSingleBean(PollerMarker.class).doesNotHaveBean(WebOrBotMarker.class));
    }

    @Test
    void roleNamesAreCaseAndSpaceInsensitive() {
        runner.withPropertyValues("earlybird.roles=WEB, Poller")
                .run(ctx -> assertThat(ctx).hasSingleBean(PollerMarker.class).hasSingleBean(WebOrBotMarker.class));
    }

    @Test
    void aMisspelledRoleStopsStartup() {
        runner.withPropertyValues("earlybird.roles=web,pollr").run(ctx -> assertThat(ctx).hasFailed());
    }
}
