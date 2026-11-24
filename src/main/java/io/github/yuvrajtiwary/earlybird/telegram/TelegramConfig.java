package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.enrich.GeminiBudget;
import io.github.yuvrajtiwary.earlybird.ops.DistributedLock;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.BotSessionRepository;
import io.github.yuvrajtiwary.earlybird.persistence.OpsStatsRepository;
import io.github.yuvrajtiwary.earlybird.persistence.TelegramUpdateRepository;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.poller.TickService;
import io.github.yuvrajtiwary.earlybird.profiles.ProfileService;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeAnalyzer;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class TelegramConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramConfig.class);

    /** The client is shared by the bot and (Block 4) the notifier. */
    @Bean
    @ConditionalOnRole({Role.BOT, Role.NOTIFIER})
    TelegramApi telegramApi(EarlyBirdProperties properties, RateLimiter rateLimiter) {
        var telegram = properties.telegram();
        return new TelegramApi(telegram.apiBaseUrl(), telegram.botToken(), rateLimiter, telegram.globalRatePerSec());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRole(Role.BOT)
    static class BotBeans {

        @Bean
        SessionStore sessionStore(BotSessionRepository sessions, ObjectMapper mapper, Clock clock) {
            return new SessionStore(sessions, mapper, clock);
        }

        @Bean
        ProfileFlows profileFlows(TelegramApi api, UserRepository users, SessionStore sessions,
                ProfileService profiles, Clock clock, ObjectProvider<ProfileFlows.RecentMatches> recentMatches) {
            return new ProfileFlows(api, users, sessions, profiles, clock, recentMatches);
        }

        @Bean
        ResumeUpload resumeUpload(TelegramApi api, SessionStore sessions, ProfileService profiles,
                ResumeAnalyzer analyzer, ProfileFlows flows) {
            return new ResumeUpload(api, sessions, profiles, analyzer, flows);
        }

        @Bean
        AdminCommands adminCommands(TelegramApi api, OpsStatsRepository stats, ObjectProvider<GeminiBudget> budget,
                ObjectProvider<TickService> ticks, DistributedLock lock, RateLimiter rateLimiter, Clock clock) {
            return new AdminCommands(api, stats, budget, ticks, lock, rateLimiter, clock);
        }

        @Bean
        Bot bot(TelegramApi api, UserRepository users, AlertRepository alerts, SessionStore sessions,
                ProfileService profiles, ProfileFlows flows, ResumeUpload resume, AdminCommands admin,
                RateLimiter rateLimiter, Clock clock, EarlyBirdProperties properties) {
            return new Bot(api, users, alerts, sessions, profiles, flows, resume, admin, rateLimiter, clock,
                    properties.telegram().adminChatId());
        }

        @Bean(destroyMethod = "close")
        UpdateDispatcher updateDispatcher(TelegramUpdateRepository seenUpdates, Bot bot) {
            return new UpdateDispatcher(seenUpdates, bot);
        }

        @Bean
        @ConditionalOnProperty(name = "earlybird.telegram.mode", havingValue = "polling", matchIfMissing = true)
        PollingReceiver pollingReceiver(TelegramApi api, UpdateDispatcher dispatcher) {
            return new PollingReceiver(api, dispatcher);
        }

        /** Webhook mode: point Telegram at us and publish the command menu once the app is up. */
        @Bean
        @ConditionalOnProperty(name = "earlybird.telegram.mode", havingValue = "webhook")
        ApplicationListener<ApplicationReadyEvent> webhookRegistrar(TelegramApi api, EarlyBirdProperties properties) {
            return event -> {
                String url = properties.publicBaseUrl().toString().replaceAll("/+$", "") + WebhookController.PATH;
                try {
                    api.setWebhook(url, properties.telegram().webhookSecret());
                    api.setMyCommands(Commands.MENU);
                    log.info("Telegram: webhook set to {}", url);
                } catch (TelegramException e) {
                    // Not fatal: the app still serves pages, and a redeploy or restart tries again.
                    log.error("Telegram: could not set webhook: {}", e.getMessage());
                }
            };
        }
    }
}
