package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.poller.TickService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the tick every 3 minutes inside the app ({@code SCHEDULER_MODE=internal}): local development, and
 * the Kubernetes poller pod. In production on Render, QStash calls {@code /internal/tick} instead (Block 6),
 * because a free Render instance would otherwise sleep after 15 idle minutes.
 *
 * <p>The tick takes a lock first, so if a tick runs long, the next one is skipped rather than overlapping.
 */
public class InternalScheduler {

    private static final Logger log = LoggerFactory.getLogger(InternalScheduler.class);

    static final String TICK_LOCK = "tick";
    static final Duration TICK_LOCK_TTL = Duration.ofMinutes(10);

    private final DistributedLock lock;
    private final ObjectProvider<TickService> ticks;

    public InternalScheduler(DistributedLock lock, ObjectProvider<TickService> ticks) {
        this.lock = lock;
        this.ticks = ticks;
    }

    @Scheduled(initialDelayString = "${earlybird.scheduler.first-tick-delay:PT20S}", fixedRateString = "PT3M")
    public void tick() {
        TickService tickService = ticks.getIfAvailable();
        if (tickService == null) {
            log.warn("Scheduler role is on but the poller role is off; nothing to tick");
            return;
        }
        lock.tryAcquire(TICK_LOCK, TICK_LOCK_TTL).ifPresentOrElse(handle -> {
            try (handle) {
                tickService.runTick();
            } catch (RuntimeException e) {
                log.error("Tick failed", e);
            }
        }, () -> log.info("Tick skipped: the previous tick is still running"));
    }
}
