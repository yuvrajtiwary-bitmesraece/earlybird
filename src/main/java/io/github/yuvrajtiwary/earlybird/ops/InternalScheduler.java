package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.alerts.DigestService;
import io.github.yuvrajtiwary.earlybird.poller.TickService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Timed jobs inside the app ({@code SCHEDULER_MODE=internal}): local development, and the Kubernetes poller
 * pod. In production on Render, QStash calls {@code /internal/tick} and {@code /internal/digest} instead
 * (Block 6), because a free Render instance would otherwise sleep after 15 idle minutes.
 *
 * <p>Each job takes a lock first, so a slow run makes the next one skip rather than overlap.
 */
public class InternalScheduler {

    private static final Logger log = LoggerFactory.getLogger(InternalScheduler.class);

    public static final String TICK_LOCK = "tick";
    public static final Duration TICK_LOCK_TTL = Duration.ofMinutes(10);
    public static final String DIGEST_LOCK = "digest";
    public static final Duration DIGEST_LOCK_TTL = Duration.ofMinutes(30);

    private final DistributedLock lock;
    private final ObjectProvider<TickService> ticks;
    private final ObjectProvider<DigestService> digests;

    public InternalScheduler(DistributedLock lock, ObjectProvider<TickService> ticks,
            ObjectProvider<DigestService> digests) {
        this.lock = lock;
        this.ticks = ticks;
        this.digests = digests;
    }

    @Scheduled(initialDelayString = "${earlybird.scheduler.first-tick-delay:PT20S}", fixedRateString = "PT3M")
    public void tick() {
        TickService tickService = ticks.getIfAvailable();
        if (tickService == null) {
            log.warn("Scheduler role is on but the poller role is off; nothing to tick");
            return;
        }
        runLocked(TICK_LOCK, TICK_LOCK_TTL, tickService::runTick);
    }

    /** 20:00 India time, every day (spec 3.3). */
    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Kolkata")
    public void digest() {
        DigestService digestService = digests.getIfAvailable();
        if (digestService == null) {
            log.warn("Scheduler role is on but the notifier role is off; no digest");
            return;
        }
        runLocked(DIGEST_LOCK, DIGEST_LOCK_TTL, digestService::sendAll);
    }

    private void runLocked(String name, Duration ttl, Runnable job) {
        lock.tryAcquire(name, ttl).ifPresentOrElse(handle -> {
            try (handle) {
                job.run();
            } catch (RuntimeException e) {
                log.error("Scheduled {} failed", name, e);
            }
        }, () -> log.info("{} skipped: the previous run is still going", name));
    }
}
