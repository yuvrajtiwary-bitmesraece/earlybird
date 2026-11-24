package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import io.github.yuvrajtiwary.earlybird.telegram.api.Update;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Local development ({@code TELEGRAM_MODE=polling}): long-polls getUpdates on a virtual thread. Needs no
 * public URL, so it works on a laptop. Use a separate dev bot: a bot can have a webhook or be polled, never
 * both, so polling the production bot would break production.
 */
class PollingReceiver implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PollingReceiver.class);
    private static final int LONG_POLL_SECONDS = 25;

    private final TelegramApi api;
    private final UpdateDispatcher dispatcher;
    private volatile boolean running;
    private Thread thread;

    PollingReceiver(TelegramApi api, UpdateDispatcher dispatcher) {
        this.api = api;
        this.dispatcher = dispatcher;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("telegram-polling").start(this::loop);
    }

    private void loop() {
        try {
            api.deleteWebhook(); // polling is refused while a webhook is set
            api.setMyCommands(Commands.MENU);
            log.info("Telegram: polling for updates");
        } catch (TelegramException e) {
            log.warn("Telegram setup failed: {}", e.getMessage());
        }
        long offset = 0;
        while (running) {
            try {
                List<Update> updates = api.getUpdates(offset, LONG_POLL_SECONDS);
                for (Update update : updates) {
                    while (running && !dispatcher.dispatch(update)) {
                        Thread.sleep(200); // lane full: wait rather than drop
                    }
                    offset = update.updateId() + 1;
                }
            } catch (TelegramException e) {
                if (!running) {
                    break;
                }
                // 409: another process polls this bot, or a webhook is set.
                log.warn("getUpdates failed: {}", e.getMessage());
                pause(e.errorCode() == 409 ? Duration.ofSeconds(10) : Duration.ofSeconds(3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
