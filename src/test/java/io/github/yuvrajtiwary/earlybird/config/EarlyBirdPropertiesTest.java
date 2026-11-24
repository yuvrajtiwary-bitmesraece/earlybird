package io.github.yuvrajtiwary.earlybird.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties.EventBusType;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties.SchedulerMode;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties.TelegramMode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EarlyBirdPropertiesTest {

    @EnableConfigurationProperties(EarlyBirdProperties.class)
    static class Config {}

    /** A complete, valid local-development configuration. Each test changes only what it is about. */
    private static final String[] VALID = {
        "earlybird.env=dev",
        "earlybird.roles=web,bot,poller,enricher,matcher,notifier,scheduler",
        "earlybird.db-schema=earlybird_dev",
        "earlybird.public-base-url=http://localhost:8080",
        "earlybird.redis-url=",
        "earlybird.metrics-token=",
        "earlybird.events.bus=memory",
        "earlybird.events.topic-prefix=eb.",
        "earlybird.kafka.security-protocol=SSL",
        "earlybird.kafka.sasl-mechanism=SCRAM-SHA-256",
        "earlybird.telegram.bot-token=123456:test-token",
        "earlybird.telegram.bot-username=EarlyBirdTestBot",
        "earlybird.telegram.mode=polling",
        "earlybird.telegram.admin-chat-id=42",
        "earlybird.telegram.global-rate-per-sec=25",
        "earlybird.gemini.api-key=test-gemini-key",
        "earlybird.gemini.model=gemini-3.5-flash-lite",
        "earlybird.gemini.max-calls-per-day=400",
        "earlybird.scheduler.mode=internal",
        "earlybird.scheduler.adaptive=false",
        "earlybird.alerts.default-min-score=55",
        "earlybird.alerts.default-daily-cap=10",
        "earlybird.alerts.prep-link-template=",
    };

    private static ApplicationContextRunner runner(String... overrides) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(VALID)
                .withPropertyValues(overrides);
    }

    @Test
    void bindsAValidLocalConfiguration() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            EarlyBirdProperties p = ctx.getBean(EarlyBirdProperties.class);
            assertThat(p.roles()).containsExactlyInAnyOrder(Role.values());
            assertThat(p.events().bus()).isEqualTo(EventBusType.MEMORY);
            assertThat(p.telegram().mode()).isEqualTo(TelegramMode.POLLING);
            assertThat(p.telegram().adminChatId()).isEqualTo(42L);
            assertThat(p.scheduler().mode()).isEqualTo(SchedulerMode.INTERNAL);
        });
    }

    @Test
    void acceptsAProductionWebhookConfiguration() {
        runner("earlybird.env=prod",
                        "earlybird.db-schema=earlybird",
                        "earlybird.public-base-url=https://earlybird.onrender.com",
                        "earlybird.telegram.mode=webhook",
                        "earlybird.telegram.webhook-secret=" + "f".repeat(64),
                        "earlybird.scheduler.mode=external",
                        "earlybird.scheduler.qstash-current-signing-key=sig_current",
                        "earlybird.scheduler.qstash-next-signing-key=sig_next")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void requirementsFollowTheEnabledRoles() {
        // A web-only process needs neither the bot token nor a Gemini key.
        runner("earlybird.roles=web", "earlybird.telegram.bot-token=", "earlybird.gemini.api-key=")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    static Stream<Arguments> invalidConfigurations() {
        String kafka = "earlybird.events.bus=kafka";
        String servers = "earlybird.kafka.bootstrap-servers=kafka.example.com:12345";
        String ca = "earlybird.kafka.ssl-ca-pem=-----BEGIN CERTIFICATE-----";
        return Stream.of(
                arguments("no roles", new String[] {"earlybird.roles="}, "EARLYBIRD_ROLES"),
                arguments("bad environment name", new String[] {"earlybird.env=Prod!"}, "EARLYBIRD_ENV"),
                arguments("unsafe schema name", new String[] {"earlybird.db-schema=x; drop table users"}, "DB_SCHEMA"),
                arguments("relative base URL", new String[] {"earlybird.public-base-url=localhost:8080"},
                        "PUBLIC_BASE_URL"),
                arguments("bot role without a token", new String[] {"earlybird.telegram.bot-token="},
                        "TELEGRAM_BOT_TOKEN"),
                arguments("webhook without a secret",
                        new String[] {"earlybird.telegram.mode=webhook",
                            "earlybird.public-base-url=https://eb.example.com"},
                        "TELEGRAM_WEBHOOK_SECRET"),
                arguments("webhook with a short secret",
                        new String[] {"earlybird.telegram.mode=webhook",
                            "earlybird.public-base-url=https://eb.example.com",
                            "earlybird.telegram.webhook-secret=short"},
                        "TELEGRAM_WEBHOOK_SECRET"),
                arguments("webhook over plain http",
                        new String[] {"earlybird.telegram.mode=webhook",
                            "earlybird.telegram.webhook-secret=" + "a".repeat(40)},
                        "must use https"),
                arguments("send rate above Telegram's limit",
                        new String[] {"earlybird.telegram.global-rate-per-sec=31"}, "TELEGRAM_GLOBAL_RATE_PER_SEC"),
                arguments("enricher without a Gemini key", new String[] {"earlybird.gemini.api-key="},
                        "GEMINI_API_KEY"),
                arguments("Kafka without bootstrap servers", new String[] {kafka}, "KAFKA_BOOTSTRAP_SERVERS"),
                arguments("Kafka SSL without a client certificate", new String[] {kafka, servers, ca},
                        "KAFKA_SSL_CERT_PEM"),
                arguments("Kafka SASL without a password",
                        new String[] {kafka, servers, ca, "earlybird.kafka.security-protocol=SASL_SSL",
                            "earlybird.kafka.sasl-username=avnadmin"},
                        "KAFKA_SASL_PASSWORD"),
                arguments("bad topic prefix", new String[] {"earlybird.events.topic-prefix=eb/"},
                        "KAFKA_TOPIC_PREFIX"),
                arguments("external scheduler without QStash keys",
                        new String[] {"earlybird.scheduler.mode=external"}, "QSTASH_CURRENT_SIGNING_KEY"),
                arguments("min score above 100", new String[] {"earlybird.alerts.default-min-score=101"},
                        "ALERT_DEFAULT_MIN_SCORE"),
                arguments("daily cap of zero", new String[] {"earlybird.alerts.default-daily-cap=0"},
                        "ALERT_DEFAULT_DAILY_CAP"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void rejectsInvalidConfiguration(String description, String[] overrides, String expectedMessage) {
        runner(overrides).run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining(expectedMessage));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        runner("earlybird.telegram.bot-token=", "earlybird.gemini.api-key=", "earlybird.alerts.default-min-score=-1")
                .run(ctx -> assertThat(ctx).getFailure().rootCause()
                        .hasMessageContainingAll("TELEGRAM_BOT_TOKEN", "GEMINI_API_KEY", "ALERT_DEFAULT_MIN_SCORE"));
    }

    @Test
    void toStringNeverContainsSecrets() {
        runner("earlybird.telegram.bot-token=123:SECRET-TOKEN",
                        "earlybird.gemini.api-key=SECRET-KEY",
                        "earlybird.redis-url=rediss://default:SECRET-PASSWORD@example.upstash.io:6379")
                .run(ctx -> {
                    String text = ctx.getBean(EarlyBirdProperties.class).toString();
                    assertThat(text).doesNotContain("SECRET").contains("botToken=<set>", "apiKey=<set>");
                });
    }

    /** Loads the real application.yml to prove the spec's environment variable names map to the right fields. */
    @Test
    void applicationYmlMapsTheSpecEnvironmentVariables() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .withPropertyValues(
                        // A profile with no file, so the local profile (which reads .env) stays off.
                        "spring.profiles.active=mappingtest",
                        "EARLYBIRD_ENV=prod",
                        "EARLYBIRD_ROLES=web,poller",
                        "DB_SCHEMA=earlybird_x",
                        "TELEGRAM_BOT_USERNAME=SomeBot",
                        "ADMIN_TELEGRAM_CHAT_ID=5546805864",
                        "GEMINI_MAX_CALLS_PER_DAY=123",
                        "ALERT_DEFAULT_DAILY_CAP=7")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EarlyBirdProperties p = ctx.getBean(EarlyBirdProperties.class);
                    assertThat(p.env()).isEqualTo("prod");
                    assertThat(p.roles()).containsExactlyInAnyOrder(Role.WEB, Role.POLLER);
                    assertThat(p.dbSchema()).isEqualTo("earlybird_x");
                    assertThat(p.telegram().botUsername()).isEqualTo("SomeBot");
                    assertThat(p.telegram().adminChatId()).isEqualTo(5546805864L);
                    assertThat(p.gemini().maxCallsPerDay()).isEqualTo(123);
                    assertThat(p.alerts().defaultDailyCap()).isEqualTo(7);
                    // Defaults for everything not set
                    assertThat(p.events().bus()).isEqualTo(EventBusType.MEMORY);
                    assertThat(p.events().topicPrefix()).isEqualTo("eb.");
                    assertThat(p.gemini().model()).isEqualTo("gemini-3.5-flash-lite");
                    assertThat(p.alerts().defaultMinScore()).isEqualTo(55);
                    assertThat(p.scheduler().mode()).isEqualTo(SchedulerMode.INTERNAL);
                    assertThat(p.telegram().globalRatePerSec()).isEqualTo(25);
                });
    }
}
