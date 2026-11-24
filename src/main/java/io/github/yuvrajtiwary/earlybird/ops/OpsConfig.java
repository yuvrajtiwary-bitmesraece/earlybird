package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpsConfig {

    @Bean
    AdminNotifier adminNotifier(ObjectProvider<TelegramApi> telegram, EarlyBirdProperties properties, Clock clock) {
        return new AdminNotifier(telegram, properties.telegram().adminChatId(), clock);
    }
}
