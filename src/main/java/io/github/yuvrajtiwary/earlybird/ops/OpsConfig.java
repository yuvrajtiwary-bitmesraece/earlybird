package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
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

    /** Only where a Telegram client exists: elsewhere it would take dead letters and could only log them. */
    @Bean
    @ConditionalOnRole({Role.BOT, Role.NOTIFIER})
    DeadLetterNotifier deadLetterNotifier(EventBus bus, AdminNotifier admin) {
        return new DeadLetterNotifier(bus, admin);
    }
}
