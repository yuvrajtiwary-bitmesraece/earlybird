package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;

/**
 * Kafka client settings (spec 3.2 and section 5) as plain maps, so they can be checked without a broker.
 *
 * <p>Security comes from environment variables only. With {@code SSL} (Aiven's default, client certificates)
 * the CA, certificate and key are PEM text, which Kafka reads directly, so no keystore files exist anywhere.
 * {@code SASL_SSL} uses the CA plus a username and password. {@code PLAINTEXT} is for test brokers.
 */
final class KafkaClients {

    private KafkaClients() {}

    /** Connection and security settings every client shares. */
    static Map<String, Object> common(EarlyBirdProperties.Kafka kafka, String clientId) {
        Map<String, Object> c = new HashMap<>();
        c.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers().strip());
        c.put(CommonClientConfigs.CLIENT_ID_CONFIG, clientId);
        c.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, kafka.securityProtocol().name());
        // Reconnect to an unreachable broker with gaps growing to 10 s (default: 1 s), so an outage doesn't turn
        // into a reconnect storm on a tenth of a CPU. Recovery is noticed within 10 s of Kafka coming back.
        c.put(CommonClientConfigs.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10_000);
        switch (kafka.securityProtocol()) {
            case SSL -> {
                trustCa(c, kafka);
                c.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
                c.put(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, pem(kafka.sslCertPem()));
                c.put(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, pem(kafka.sslKeyPem()));
            }
            case SASL_SSL -> {
                trustCa(c, kafka);
                c.put(SaslConfigs.SASL_MECHANISM, kafka.saslMechanism().strip());
                c.put(SaslConfigs.SASL_JAAS_CONFIG, jaas(kafka));
            }
            case PLAINTEXT -> { }
        }
        return c;
    }

    /**
     * Spec 3.2: every write confirmed by all in-sync replicas, no duplicates from the producer's own retries,
     * zstd, and up to 20 ms of batching. Timeouts are seconds, not the defaults' minutes: when Kafka is down the
     * caller should learn quickly, keep its row unpublished, and leave it to the outbox relay.
     */
    static Map<String, Object> producer(EarlyBirdProperties.Kafka kafka, String env, String compression) {
        Map<String, Object> c = common(kafka, "earlybird-" + env + "-producer");
        c.put(ProducerConfig.ACKS_CONFIG, "all");
        c.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        c.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression);
        c.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        c.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);       // waiting for metadata (default 60 s)
        c.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        c.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 12_000); // default 120 s; must be ≥ linger + request timeout
        return c;
    }

    /**
     * Spec 3.2: offsets are committed by the listener container only after the handler (and its database
     * transaction) finished, at most 20 records per poll, and topics are never created by accident.
     */
    static Map<String, Object> consumer(EarlyBirdProperties.Kafka kafka, String env) {
        Map<String, Object> c = common(kafka, "earlybird-" + env + "-consumer");
        c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // A brand-new group starts from the oldest retained event: handlers are idempotent, losing events isn't.
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 20);
        c.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        // An idle consumer's fetch waits up to 5 s at the broker (default 0.5 s), but returns as soon as an event
        // arrives, so latency doesn't change while idle traffic, and CPU on 0.1 CPU, drop tenfold.
        c.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 5_000);
        c.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10_000); // under a third of the 45 s session timeout
        return c;
    }

    static Map<String, Object> admin(EarlyBirdProperties.Kafka kafka, String env) {
        Map<String, Object> c = common(kafka, "earlybird-" + env + "-admin");
        c.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        c.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        return c;
    }

    /** zstd as the spec asks; lz4 if zstd's native library can't load on this machine. */
    static String compression() {
        try {
            Class.forName("com.github.luben.zstd.util.Native").getMethod("load").invoke(null);
            return "zstd";
        } catch (ReflectiveOperationException | LinkageError e) {
            return "lz4";
        }
    }

    /**
     * PEM text the way Kafka parses it, with real newlines. A value written on one line with literal
     * {@code \n} (as {@code scripts/KafkaPemsToEnv.java} does, and as some dashboards store it), Windows
     * newlines and surrounding quotes are all accepted.
     */
    static String pem(String value) {
        String text = value.strip();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        text = text.replace("\r\n", "\n");
        if (!text.contains("\n")) {
            text = text.replace("\\n", "\n");
        }
        return text.strip() + "\n";
    }

    private static void trustCa(Map<String, Object> c, EarlyBirdProperties.Kafka kafka) {
        c.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
        c.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, pem(kafka.sslCaPem()));
    }

    /** SCRAM (Aiven's SASL) or PLAIN; the values are quoted, with backslashes and quotes escaped. */
    static String jaas(EarlyBirdProperties.Kafka kafka) {
        String module = kafka.saslMechanism().strip().toUpperCase(Locale.ROOT).startsWith("SCRAM-")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
        return module + " required username=\"" + quoted(kafka.saslUsername()) + "\" password=\""
                + quoted(kafka.saslPassword()) + "\";";
    }

    private static String quoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
