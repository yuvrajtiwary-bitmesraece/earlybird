package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.common.AdminAlerts;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;

/** Chooses the event bus from {@code EVENT_BUS}: {@code memory} (the default, for development) or {@code kafka}. */
@Configuration(proxyBeanMethods = false)
class EventBusConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "earlybird.events.bus", havingValue = "memory", matchIfMissing = true)
    InMemoryEventBus inMemoryEventBus() {
        return new InMemoryEventBus();
    }

    /**
     * {@code EVENT_BUS=kafka}. Boot's Kafka auto-configuration isn't on the classpath (only spring-kafka is), so
     * these few beans are all the Kafka there is, and none of them exist with the in-process bus.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "earlybird.events.bus", havingValue = "kafka")
    static class Kafka {

        private static final Logger log = LoggerFactory.getLogger(Kafka.class);

        @Bean(destroyMethod = "destroy")
        DefaultKafkaProducerFactory<String, byte[]> kafkaProducerFactory(EarlyBirdProperties properties,
                MeterRegistry meters) {
            String compression = KafkaClients.compression();
            if (!"zstd".equals(compression)) {
                log.warn("zstd can't load on this machine; events are compressed with {} instead", compression);
            }
            var factory = new DefaultKafkaProducerFactory<>(
                    KafkaClients.producer(properties.kafka(), properties.env(), compression),
                    new StringSerializer(), new ByteArraySerializer());
            factory.addListener(new MicrometerProducerListener<>(meters));
            factory.setPhysicalCloseTimeout(10);
            return factory;
        }

        @Bean
        KafkaTemplate<String, byte[]> kafkaTemplate(DefaultKafkaProducerFactory<String, byte[]> producers) {
            return new KafkaTemplate<>(producers);
        }

        @Bean
        DefaultKafkaConsumerFactory<String, byte[]> kafkaConsumerFactory(EarlyBirdProperties properties,
                MeterRegistry meters) {
            var factory = new DefaultKafkaConsumerFactory<>(KafkaClients.consumer(properties.kafka(), properties.env()),
                    new StringDeserializer(), new ByteArrayDeserializer());
            factory.addListener(new MicrometerConsumerListener<>(meters));
            return factory;
        }

        @Bean(destroyMethod = "close")
        KafkaMonitor.AdminCluster kafkaCluster(EarlyBirdProperties properties) {
            return new KafkaMonitor.AdminCluster(KafkaClients.admin(properties.kafka(), properties.env()));
        }

        @Bean
        KafkaMonitor kafkaMonitor(KafkaMonitor.AdminCluster kafkaCluster, EarlyBirdProperties properties,
                AdminAlerts admin, Clock clock) {
            return new KafkaMonitor(kafkaCluster, properties.events().topicPrefix(), admin, clock);
        }

        /** Shown as "kafka" in /actuator/health; DEGRADED while Kafka is unreachable or topics are missing. */
        @Bean
        HealthIndicator kafkaHealthIndicator(KafkaMonitor monitor) {
            return monitor::health;
        }

        @Bean
        KafkaEventBus kafkaEventBus(KafkaTemplate<String, byte[]> template,
                DefaultKafkaConsumerFactory<String, byte[]> consumers, KafkaMonitor monitor,
                EarlyBirdProperties properties, Clock clock, MeterRegistry meters) {
            return new KafkaEventBus(template, consumers, new EventJson(), monitor,
                    properties.events().topicPrefix(), properties.env(), properties.events().retryBackoff(), clock,
                    meters);
        }
    }
}
