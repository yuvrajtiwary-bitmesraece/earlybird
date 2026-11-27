package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.ops.InternalJobs.Job;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Timed jobs inside the app ({@code SCHEDULER_MODE=internal}): local development and the Kubernetes poller pod.
 * In production on Render, QStash calls {@code /internal/*} instead (InternalController), because a free Render
 * instance sleeps after 15 idle minutes and its own timers would stop. Both go through InternalJobs, so the
 * locks and the work are the same.
 */
public class InternalScheduler {

    private final InternalJobs jobs;

    public InternalScheduler(InternalJobs jobs) {
        this.jobs = jobs;
    }

    @Scheduled(initialDelayString = "${earlybird.scheduler.first-tick-delay:PT20S}", fixedRateString = "PT3M")
    public void tick() {
        jobs.runNow(Job.TICK);
    }

    /** 20:00 India time, every day (spec 3.3). */
    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Kolkata")
    public void digest() {
        jobs.runNow(Job.DIGEST);
    }

    /** 03:30 India time, every day (spec 3.3). */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    public void maintenance() {
        jobs.runNow(Job.MAINTENANCE);
    }
}
