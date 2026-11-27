package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.alerts.DigestService;
import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.MaintenanceRepository;
import io.github.yuvrajtiwary.earlybird.poller.TickService;
import io.github.yuvrajtiwary.earlybird.sources.JobSources;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    /** Redis SET NX PX when Redis is configured and up; a Postgres advisory lock otherwise (spec 3.3). */
    @Bean
    DistributedLock distributedLock(DataSource dataSource, EarlyBirdProperties properties,
            ObjectProvider<RedisGuard> guard, ObjectProvider<StringRedisTemplate> redis) {
        DistributedLock postgres = new PostgresAdvisoryLock(dataSource, properties.dbSchema());
        RedisGuard g = guard.getIfAvailable();
        return g == null ? postgres : new RedisLock(redis.getObject(), g, postgres);
    }

    @Bean
    QStashVerifier qstashVerifier(EarlyBirdProperties properties, Clock clock) {
        return new QStashVerifier(properties.scheduler().qstashCurrentSigningKey(),
                properties.scheduler().qstashNextSigningKey(), clock);
    }

    @Bean
    @ConditionalOnRole(Role.SCHEDULER)
    MaintenanceService maintenanceService(MaintenanceRepository repository, BoardRepository boards,
            ObjectProvider<JobSources> sources, Clock clock) {
        return new MaintenanceService(repository, boards, sources, clock);
    }

    @Bean
    InternalJobs internalJobs(DistributedLock lock, ObjectProvider<TickService> ticks,
            ObjectProvider<DigestService> digests, ObjectProvider<MaintenanceService> maintenance) {
        return new InternalJobs(lock, ticks, digests, maintenance);
    }
}
