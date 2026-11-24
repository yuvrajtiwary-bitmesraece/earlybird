package io.github.yuvrajtiwary.earlybird.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the event bus from {@code EVENT_BUS}. The Kafka implementation is added in Block 5. */
@Configuration(proxyBeanMethods = false)
class EventBusConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "earlybird.events.bus", havingValue = "memory", matchIfMissing = true)
    InMemoryEventBus inMemoryEventBus() {
        return new InMemoryEventBus();
    }
}
