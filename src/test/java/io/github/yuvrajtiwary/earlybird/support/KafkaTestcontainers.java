package io.github.yuvrajtiwary.earlybird.support;

import io.github.yuvrajtiwary.earlybird.events.Topic;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A real single-node Kafka (the official apache/kafka image, KRaft) for {@link KafkaIntegrationTest}, set up like
 * Aiven's: auto-creation off, and the five topics made by hand with 2 partitions each before the app starts.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainers {

    /** The same version as the kafka-clients library Spring Boot manages. */
    public static final String IMAGE = "apache/kafka:4.4.0";

    @Bean(destroyMethod = "stop")
    KafkaContainer kafka() throws Exception {
        var kafka = new KafkaContainer(IMAGE).withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        kafka.start();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(Arrays.stream(Topic.values())
                            .map(t -> new NewTopic(t.topicName("eb."), 2, (short) 1))
                            .toList())
                    .all().get(60, TimeUnit.SECONDS);
        }
        return kafka;
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
        return registry -> {
            registry.add("earlybird.kafka.bootstrap-servers", kafka::getBootstrapServers);
            registry.add("earlybird.kafka.security-protocol", () -> "PLAINTEXT");
        };
    }
}
