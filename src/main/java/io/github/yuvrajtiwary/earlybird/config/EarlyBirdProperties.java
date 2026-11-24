package io.github.yuvrajtiwary.earlybird.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every EarlyBird setting. application.yml maps the environment variables from the spec (section 5) onto
 * these fields and holds the defaults.
 *
 * <p>Validated when bound: if anything is wrong, startup stops and lists every problem at once, each naming
 * the environment variable to fix. Requirements depend on the enabled roles, so a process running only the
 * {@code web} role doesn't need a Gemini key. {@code toString()} masks secrets so they can't leak into logs.
 */
@ConfigurationProperties(prefix = "earlybird")
public record EarlyBirdProperties(
        String env,
        Set<Role> roles,
        String dbSchema,
        URI publicBaseUrl,
        String redisUrl,
        String metricsToken,
        @DefaultValue Events events,
        @DefaultValue Kafka kafka,
        @DefaultValue Telegram telegram,
        @DefaultValue Gemini gemini,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Alerts alerts,
        @DefaultValue Sources sources) {

    private static final Pattern ENV_NAME = Pattern.compile("[a-z0-9-]{1,20}");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern TOPIC_PREFIX = Pattern.compile("[a-zA-Z0-9._-]{0,100}");
    // Telegram's secret_token rules: 1-256 chars of A-Z a-z 0-9 _ -. We also require 32+ for strength.
    private static final Pattern WEBHOOK_SECRET = Pattern.compile("[A-Za-z0-9_-]{32,256}");

    public EarlyBirdProperties {
        roles = (roles == null || roles.isEmpty()) ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(roles));
        List<String> problems = problems(
                env, roles, dbSchema, publicBaseUrl, events, kafka, telegram, gemini, scheduler, alerts, sources);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid EarlyBird configuration:\n  - " + String.join("\n  - ", problems));
        }
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public enum EventBusType { MEMORY, KAFKA }

    public enum KafkaSecurity { SSL, SASL_SSL }

    public enum TelegramMode { WEBHOOK, POLLING }

    public enum SchedulerMode { INTERNAL, EXTERNAL }

    public record Events(EventBusType bus, String topicPrefix) {}

    public record Kafka(
            String bootstrapServers,
            KafkaSecurity securityProtocol,
            String sslCaPem,
            String sslCertPem,
            String sslKeyPem,
            String saslMechanism,
            String saslUsername,
            String saslPassword) {

        @Override
        public String toString() {
            return "Kafka[bootstrapServers=" + bootstrapServers + ", securityProtocol=" + securityProtocol
                    + ", sslCaPem=" + mask(sslCaPem) + ", sslCertPem=" + mask(sslCertPem)
                    + ", sslKeyPem=" + mask(sslKeyPem) + ", saslMechanism=" + saslMechanism
                    + ", saslUsername=" + saslUsername + ", saslPassword=" + mask(saslPassword) + "]";
        }
    }

    /** @param apiBaseUrl overridden only by tests, which point it at a fake Telegram */
    public record Telegram(
            String botToken,
            String botUsername,
            TelegramMode mode,
            String webhookSecret,
            Long adminChatId,
            int globalRatePerSec,
            @DefaultValue("https://api.telegram.org") URI apiBaseUrl) {

        @Override
        public String toString() {
            return "Telegram[botToken=" + mask(botToken) + ", botUsername=" + botUsername + ", mode=" + mode
                    + ", webhookSecret=" + mask(webhookSecret) + ", adminChatId=" + adminChatId
                    + ", globalRatePerSec=" + globalRatePerSec + ", apiBaseUrl=" + apiBaseUrl + "]";
        }
    }

    /** @param baseUrl overridden only by tests, which point it at a fake Gemini */
    public record Gemini(
            String apiKey,
            String model,
            int maxCallsPerDay,
            @DefaultValue("https://generativelanguage.googleapis.com") URI baseUrl) {

        @Override
        public String toString() {
            return "Gemini[apiKey=" + mask(apiKey) + ", model=" + model + ", maxCallsPerDay=" + maxCallsPerDay
                    + ", baseUrl=" + baseUrl + "]";
        }
    }

    public record Scheduler(
            SchedulerMode mode, String qstashCurrentSigningKey, String qstashNextSigningKey, boolean adaptive) {

        @Override
        public String toString() {
            return "Scheduler[mode=" + mode + ", qstashCurrentSigningKey=" + mask(qstashCurrentSigningKey)
                    + ", qstashNextSigningKey=" + mask(qstashNextSigningKey) + ", adaptive=" + adaptive + "]";
        }
    }

    public record Alerts(int defaultMinScore, int defaultDailyCap, String prepLinkTemplate) {}

    /**
     * Where the job-board APIs live (overridden only by tests, which point them at a fake server) and the
     * seed list loaded at startup. An empty {@code seedFile} skips seeding.
     */
    public record Sources(
            @DefaultValue("https://boards-api.greenhouse.io") URI greenhouseBaseUrl,
            @DefaultValue("https://api.lever.co") URI leverBaseUrl,
            @DefaultValue("https://api.ashbyhq.com") URI ashbyBaseUrl,
            @DefaultValue("classpath:boards/seed-boards.csv") String seedFile) {}

    @Override
    public String toString() {
        return "EarlyBirdProperties[env=" + env + ", roles=" + roles + ", dbSchema=" + dbSchema
                + ", publicBaseUrl=" + publicBaseUrl + ", redisUrl=" + mask(redisUrl)
                + ", metricsToken=" + mask(metricsToken) + ", events=" + events + ", kafka=" + kafka
                + ", telegram=" + telegram + ", gemini=" + gemini + ", scheduler=" + scheduler
                + ", alerts=" + alerts + ", sources=" + sources + "]";
    }

    private static boolean isHttpUrl(URI uri) {
        return uri != null
                && uri.isAbsolute()
                && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null;
    }

    private static String mask(String secret) {
        return blank(secret) ? "<empty>" : "<set>";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> problems(
            String env,
            Set<Role> roles,
            String dbSchema,
            URI publicBaseUrl,
            Events events,
            Kafka kafka,
            Telegram telegram,
            Gemini gemini,
            Scheduler scheduler,
            Alerts alerts,
            Sources sources) {
        var p = new ArrayList<String>();
        boolean web = roles.contains(Role.WEB);
        boolean bot = roles.contains(Role.BOT);
        boolean notifier = roles.contains(Role.NOTIFIER);
        boolean enricher = roles.contains(Role.ENRICHER);
        boolean schedulerRole = roles.contains(Role.SCHEDULER);

        // Runtime
        if (env == null || !ENV_NAME.matcher(env).matches()) {
            p.add("EARLYBIRD_ENV must be 1-20 lowercase letters, digits or '-' (e.g. dev, prod)");
        }
        if (roles.isEmpty()) {
            p.add("EARLYBIRD_ROLES must list at least one of "
                    + Arrays.stream(Role.values()).map(Role::id).toList());
        }
        if (dbSchema == null || !SQL_IDENTIFIER.matcher(dbSchema).matches()) {
            p.add("DB_SCHEMA must be a lowercase SQL identifier (e.g. earlybird_dev)");
        }
        boolean httpUrl = isHttpUrl(publicBaseUrl);
        if (!httpUrl) {
            p.add("PUBLIC_BASE_URL must be an absolute http(s) URL (e.g. http://localhost:8080)");
        }

        // Telegram
        if ((bot || notifier) && blank(telegram.botToken())) {
            p.add("TELEGRAM_BOT_TOKEN is required when roles include bot or notifier");
        }
        if ((bot || web) && blank(telegram.botUsername())) {
            p.add("TELEGRAM_BOT_USERNAME is required when roles include bot or web");
        }
        if (telegram.mode() == null) {
            p.add("TELEGRAM_MODE must be webhook or polling");
        }
        if (bot && telegram.mode() == TelegramMode.WEBHOOK) {
            if (telegram.webhookSecret() == null || !WEBHOOK_SECRET.matcher(telegram.webhookSecret()).matches()) {
                p.add("TELEGRAM_WEBHOOK_SECRET must be 32-256 characters of A-Z, a-z, 0-9, '_' or '-' in webhook mode");
            }
            if (httpUrl && !"https".equals(publicBaseUrl.getScheme())) {
                p.add("PUBLIC_BASE_URL must use https in webhook mode (Telegram only calls https webhooks)");
            }
        }
        if (telegram.globalRatePerSec() < 1 || telegram.globalRatePerSec() > 30) {
            p.add("TELEGRAM_GLOBAL_RATE_PER_SEC must be between 1 and 30 (Telegram's bulk limit is about 30/s)");
        }

        // Gemini
        if ((enricher || bot) && blank(gemini.apiKey())) {
            p.add("GEMINI_API_KEY is required when roles include enricher or bot");
        }
        if (blank(gemini.model())) {
            p.add("GEMINI_MODEL must not be empty");
        }
        if (gemini.maxCallsPerDay() < 0) {
            p.add("GEMINI_MAX_CALLS_PER_DAY must be 0 or more");
        }

        // Event bus
        if (events.bus() == null) {
            p.add("EVENT_BUS must be memory or kafka");
        }
        if (events.topicPrefix() == null || !TOPIC_PREFIX.matcher(events.topicPrefix()).matches()) {
            p.add("KAFKA_TOPIC_PREFIX may only contain letters, digits, '.', '_' or '-'");
        }
        if (events.bus() == EventBusType.KAFKA) {
            if (blank(kafka.bootstrapServers())) {
                p.add("KAFKA_BOOTSTRAP_SERVERS is required when EVENT_BUS=kafka");
            }
            if (kafka.securityProtocol() == null) {
                p.add("KAFKA_SECURITY_PROTOCOL must be SSL or SASL_SSL");
            } else {
                if (blank(kafka.sslCaPem())) {
                    p.add("KAFKA_SSL_CA_PEM is required when EVENT_BUS=kafka");
                }
                if (kafka.securityProtocol() == KafkaSecurity.SSL
                        && (blank(kafka.sslCertPem()) || blank(kafka.sslKeyPem()))) {
                    p.add("KAFKA_SSL_CERT_PEM and KAFKA_SSL_KEY_PEM are required when KAFKA_SECURITY_PROTOCOL=SSL");
                }
                if (kafka.securityProtocol() == KafkaSecurity.SASL_SSL
                        && (blank(kafka.saslMechanism()) || blank(kafka.saslUsername())
                                || blank(kafka.saslPassword()))) {
                    p.add("KAFKA_SASL_MECHANISM, KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD are required"
                            + " when KAFKA_SECURITY_PROTOCOL=SASL_SSL");
                }
            }
        }

        // Scheduling
        if (scheduler.mode() == null) {
            p.add("SCHEDULER_MODE must be internal or external");
        }
        if (schedulerRole && scheduler.mode() == SchedulerMode.EXTERNAL
                && (blank(scheduler.qstashCurrentSigningKey()) || blank(scheduler.qstashNextSigningKey()))) {
            p.add("QSTASH_CURRENT_SIGNING_KEY and QSTASH_NEXT_SIGNING_KEY are required when SCHEDULER_MODE=external");
        }

        // Alerts
        if (alerts.defaultMinScore() < 0 || alerts.defaultMinScore() > 100) {
            p.add("ALERT_DEFAULT_MIN_SCORE must be between 0 and 100");
        }
        if (alerts.defaultDailyCap() < 1 || alerts.defaultDailyCap() > 100) {
            p.add("ALERT_DEFAULT_DAILY_CAP must be between 1 and 100");
        }

        // Job sources
        if (roles.contains(Role.POLLER)) {
            for (URI url : Arrays.asList(
                    sources.greenhouseBaseUrl(), sources.leverBaseUrl(), sources.ashbyBaseUrl())) {
                if (!isHttpUrl(url)) {
                    p.add("earlybird.sources base URLs must be absolute http(s) URLs, got " + url);
                }
            }
        }
        return p;
    }
}
