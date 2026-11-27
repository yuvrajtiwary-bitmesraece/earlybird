package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.alerts.DigestService;
import io.github.yuvrajtiwary.earlybird.poller.TickService;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The three timed jobs (spec 3.3), each behind its own lock so a run that's still going makes the next one skip
 * rather than overlap. Started either by QStash through {@code /internal/*} (production) or by the internal
 * scheduler (local development), through the same code.
 */
public class InternalJobs {

    private static final Logger log = LoggerFactory.getLogger(InternalJobs.class);

    public enum Job {
        /** Lease longer than any tick (~1 min), in case a holder dies without releasing. */
        TICK(Duration.ofMinutes(10)),
        DIGEST(Duration.ofMinutes(30)),
        MAINTENANCE(Duration.ofMinutes(30));

        private final Duration lease;

        Job(Duration lease) {
            this.lease = lease;
        }

        public String lockName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Duration lease() {
            return lease;
        }

        public static Optional<Job> fromPath(String path) {
            for (Job job : values()) {
                if (job.lockName().equals(path)) {
                    return Optional.of(job);
                }
            }
            return Optional.empty();
        }
    }

    public enum Start {
        /** Lock taken; running in the background. */
        STARTED,
        /** The previous run still holds the lock. */
        SKIPPED,
        /** This process doesn't run the module the job needs (roles). */
        NOT_HERE
    }

    private final DistributedLock lock;
    private final ObjectProvider<TickService> ticks;
    private final ObjectProvider<DigestService> digests;
    private final ObjectProvider<MaintenanceService> maintenance;

    public InternalJobs(DistributedLock lock, ObjectProvider<TickService> ticks, ObjectProvider<DigestService> digests,
            ObjectProvider<MaintenanceService> maintenance) {
        this.lock = lock;
        this.ticks = ticks;
        this.digests = digests;
        this.maintenance = maintenance;
    }

    /** Takes the lock and runs the job on a background thread, so the HTTP caller gets its 202 at once. */
    public Start start(Job job) {
        Runnable work = work(job);
        if (work == null) {
            return Start.NOT_HERE;
        }
        Optional<DistributedLock.Handle> handle = lock.tryAcquire(job.lockName(), job.lease());
        if (handle.isEmpty()) {
            log.info("{} skipped: the previous run is still going", job.lockName());
            return Start.SKIPPED;
        }
        Thread.ofVirtual().name("job-" + job.lockName()).start(() -> run(job, work, handle.get()));
        return Start.STARTED;
    }

    /** Takes the lock and runs the job on this thread (the internal scheduler's own thread). */
    public Start runNow(Job job) {
        Runnable work = work(job);
        if (work == null) {
            return Start.NOT_HERE;
        }
        Optional<DistributedLock.Handle> handle = lock.tryAcquire(job.lockName(), job.lease());
        if (handle.isEmpty()) {
            log.info("{} skipped: the previous run is still going", job.lockName());
            return Start.SKIPPED;
        }
        run(job, work, handle.get());
        return Start.STARTED;
    }

    private static void run(Job job, Runnable work, DistributedLock.Handle handle) {
        try (handle) {
            work.run();
        } catch (RuntimeException e) {
            log.error("{} failed", job.lockName(), e);
        }
    }

    private Runnable work(Job job) {
        return switch (job) {
            case TICK -> {
                TickService t = ticks.getIfAvailable();
                yield t == null ? null : t::runTick;
            }
            case DIGEST -> {
                DigestService d = digests.getIfAvailable();
                yield d == null ? null : d::sendAll;
            }
            case MAINTENANCE -> {
                MaintenanceService m = maintenance.getIfAvailable();
                yield m == null ? null : m::run;
            }
        };
    }
}
