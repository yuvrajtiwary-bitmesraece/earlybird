package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.persistence.TelegramUpdateRepository;
import io.github.yuvrajtiwary.earlybird.telegram.api.TgUser;
import io.github.yuvrajtiwary.earlybird.telegram.api.Update;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands updates to the bot without blocking the receiver (webhook or polling), on a bounded executor
 * (spec 3.11).
 *
 * <p>Each user maps to one of {@value #LANES} lanes, and a lane runs one update at a time. So one user's
 * updates are handled in order (two quick taps on a toggle can't race on their session), while different
 * users run in parallel. Each lane's queue is bounded; when it's full, {@link #dispatch} says no and the
 * webhook answers 503, so Telegram delivers the update again later instead of us queueing without limit.
 *
 * <p>Telegram may deliver an update twice; {@code telegram_updates} makes the second one a no-op.
 */
class UpdateDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UpdateDispatcher.class);

    static final int LANES = 16;
    static final int QUEUE_PER_LANE = 100;

    private final ThreadPoolExecutor[] lanes = new ThreadPoolExecutor[LANES];
    private final TelegramUpdateRepository seenUpdates;
    private final Bot bot;

    UpdateDispatcher(TelegramUpdateRepository seenUpdates, Bot bot) {
        this.seenUpdates = seenUpdates;
        this.bot = bot;
        for (int i = 0; i < LANES; i++) {
            lanes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_PER_LANE), Thread.ofVirtual().name("bot-lane-" + i).factory());
        }
    }

    /** @return false if this user's lane is full (the caller should ask Telegram to retry) */
    boolean dispatch(Update update) {
        TgUser sender = update.sender();
        long key = sender == null ? update.updateId() : sender.id();
        try {
            lanes[Math.floorMod(Long.hashCode(key), LANES)].execute(() -> process(update));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Update {} rejected: lane full", update.updateId());
            return false;
        }
    }

    private void process(Update update) {
        try {
            if (!seenUpdates.markReceived(update.updateId())) {
                return; // a redelivery we've already handled
            }
            bot.handle(update);
        } catch (RuntimeException e) {
            // User id only: no message text, names or resume content in logs.
            TgUser sender = update.sender();
            log.error("Update {} from user {} failed: {}", update.updateId(), sender == null ? "?" : sender.id(), e.toString());
        }
    }

    @Override
    public void close() {
        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
        }
        for (ThreadPoolExecutor lane : lanes) {
            try {
                if (!lane.awaitTermination(5, TimeUnit.SECONDS)) {
                    lane.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
