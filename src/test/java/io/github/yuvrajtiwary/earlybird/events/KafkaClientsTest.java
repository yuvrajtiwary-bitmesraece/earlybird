package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties.Kafka;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties.KafkaSecurity;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KafkaClientsTest {

    private static String certPem;
    private static String keyPem;

    /** A real certificate and key, made with the JDK's keytool, so Kafka's own PEM parser can be tried below. */
    @BeforeAll
    static void makeACertificate(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize",
                "2048", "-dname", "CN=earlybird-test", "-validity", "2", "-storetype", "PKCS12", "-keystore",
                store.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        certPem = pem("CERTIFICATE", keys.getCertificate("test").getEncoded());
        keyPem = pem("PRIVATE KEY", ((PrivateKey) keys.getKey("test", "changeit".toCharArray())).getEncoded());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** As scripts/KafkaPemsToEnv.java writes it into .env: one line, newlines as backslash-n. */
    private static String oneLine(String pem) {
        return pem.replace("\n", "\\n");
    }

    private static Kafka ssl(String ca, String cert, String key) {
        return new Kafka("kafka.example.com:12345", KafkaSecurity.SSL, ca, cert, key, "SCRAM-SHA-256", "", "");
    }

    @Test
    void theProducerFollowsTheSpec() {
        Map<String, Object> c = KafkaClients.producer(ssl(certPem, certPem, keyPem), "prod", "zstd");

        assertThat(c).containsEntry("acks", "all")
                .containsEntry("enable.idempotence", true)
                .containsEntry("compression.type", "zstd")
                .containsEntry("linger.ms", 20)
                .containsEntry("client.id", "earlybird-prod-producer")
                .containsEntry("bootstrap.servers", "kafka.example.com:12345");
        // Fail within seconds while Kafka is down, so the poller and tagger aren't held up; the relay retries.
        assertThat((int) c.get("delivery.timeout.ms"))
                .isGreaterThanOrEqualTo((int) c.get("linger.ms") + (int) c.get("request.timeout.ms"))
                .isLessThanOrEqualTo(15_000);
        assertThat((int) c.get("max.block.ms")).isLessThanOrEqualTo(10_000);
    }

    @Test
    void consumersCommitOnlyAfterHandlingAndNeverCreateTopics() {
        Map<String, Object> c = KafkaClients.consumer(ssl(certPem, certPem, keyPem), "dev");

        assertThat(c).containsEntry("enable.auto.commit", false)
                .containsEntry("max.poll.records", 20)
                .containsEntry("allow.auto.create.topics", false)
                .containsEntry("auto.offset.reset", "earliest");
    }

    @Test
    void sslUsesPemTextNotKeystoreFiles() {
        Map<String, Object> c = KafkaClients.common(ssl(oneLine(certPem), oneLine(certPem), oneLine(keyPem)), "x");

        assertThat(c).containsEntry("security.protocol", "SSL")
                .containsEntry("ssl.truststore.type", "PEM")
                .containsEntry("ssl.keystore.type", "PEM")
                .containsEntry("ssl.truststore.certificates", certPem)  // real newlines again
                .containsEntry("ssl.keystore.certificate.chain", certPem)
                .containsEntry("ssl.keystore.key", keyPem)
                .doesNotContainKeys("ssl.keystore.location", "ssl.truststore.location");
    }

    /** The proof that matters: Kafka's own SSL setup accepts the settings, from the one-line .env form. */
    @Test
    void kafkaAcceptsTheCertificateAndKeyAsWrittenToEnv() {
        Map<String, Object> config = KafkaClients.producer(
                new Kafka("localhost:9", KafkaSecurity.SSL, oneLine(certPem), oneLine(certPem), oneLine(keyPem),
                        "SCRAM-SHA-256", "", ""), "test", "lz4");

        // Creating the producer builds the SSL context from the PEM text; nothing connects until a send.
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer())) {
            assertThat(producer.metrics()).isNotEmpty();
        }
    }

    @Test
    void aBrokenKeyIsRefusedAtStartup() {
        String broken = keyPem.replace(keyPem.substring(40, 80), "A".repeat(40));
        Map<String, Object> config = KafkaClients.producer(
                new Kafka("localhost:9", KafkaSecurity.SSL, certPem, certPem, broken, "SCRAM-SHA-256", "", ""),
                "test", "lz4");

        assertThatThrownBy(() -> new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer()))
                .isInstanceOf(KafkaException.class);
    }

    @Test
    void pemTextIsAcceptedInEveryFormDashboardsProduce() {
        String expected = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n";

        assertThat(KafkaClients.pem(expected)).isEqualTo(expected);
        assertThat(KafkaClients.pem("-----BEGIN CERTIFICATE-----\\nAAAA\\n-----END CERTIFICATE-----\\n"))
                .isEqualTo(expected);
        assertThat(KafkaClients.pem("-----BEGIN CERTIFICATE-----\r\nAAAA\r\n-----END CERTIFICATE-----"))
                .isEqualTo(expected);
        assertThat(KafkaClients.pem("  \"-----BEGIN CERTIFICATE-----\\nAAAA\\n-----END CERTIFICATE-----\"  "))
                .isEqualTo(expected);
    }

    @Test
    void saslUsesTheCaAndAQuotedLogin() {
        var kafka = new Kafka("kafka.example.com:12345", KafkaSecurity.SASL_SSL, certPem, "", "", "SCRAM-SHA-256",
                "avnadmin", "pa\"ss\\word");

        Map<String, Object> c = KafkaClients.common(kafka, "x");

        assertThat(c).containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-256")
                .containsEntry("ssl.truststore.certificates", certPem)
                .doesNotContainKey("ssl.keystore.key");
        assertThat(c.get("sasl.jaas.config")).isEqualTo("org.apache.kafka.common.security.scram.ScramLoginModule"
                + " required username=\"avnadmin\" password=\"pa\\\"ss\\\\word\";");
    }

    @Test
    void plaintextIsForTestBrokersAndCarriesNoSecrets() {
        var kafka = new Kafka("localhost:9092", KafkaSecurity.PLAINTEXT, "", "", "", "", "", "");

        assertThat(KafkaClients.common(kafka, "x")).containsEntry("security.protocol", "PLAINTEXT")
                .doesNotContainKeys("ssl.truststore.certificates", "ssl.keystore.key", "sasl.jaas.config");
    }

    @Test
    void zstdLoadsOnThisMachine() {
        assertThat(KafkaClients.compression()).isEqualTo("zstd");
    }
}
