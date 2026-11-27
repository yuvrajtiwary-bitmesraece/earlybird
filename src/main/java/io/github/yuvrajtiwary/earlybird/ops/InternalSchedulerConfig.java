package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is switched on only here, so a process without the scheduler role never runs timed jobs. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnRole(Role.SCHEDULER)
@ConditionalOnProperty(name = "earlybird.scheduler.mode", havingValue = "internal", matchIfMissing = true)
class InternalSchedulerConfig {

    @Bean
    InternalScheduler internalScheduler(InternalJobs jobs) {
        return new InternalScheduler(jobs);
    }
}
