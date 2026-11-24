package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tells the admin on Telegram when something needs attention (spec 3.11): the Gemini budget is used up, and
 * later dead letters, failing ticks, Kafka outages. Each kind of problem is sent at most once a day, so an
 * outage doesn't turn into a message flood. A process without the Telegram client only logs.
 */
public class AdminNotifier {

    private static final Logger log = LoggerFactory.getLogger(AdminNotifier.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final ObjectProvider<TelegramApi> telegram;
    private final Long adminChatId;
    private final Clock clock;
    private final Set<String> sentToday = ConcurrentHashMap.newKeySet();

    public AdminNotifier(ObjectProvider<TelegramApi> telegram, Long adminChatId, Clock clock) {
        this.telegram = telegram;
        this.adminChatId = adminChatId;
        this.clock = clock;
    }

    /** Sends {@code html} unless a message with this {@code kind} already went out today (IST). */
    public void notifyOncePerDay(String kind, String html) {
        String key = kind + "@" + LocalDate.now(clock.withZone(INDIA));
        if (!sentToday.add(key)) {
            return;
        }
        sentToday.removeIf(k -> !k.endsWith("@" + LocalDate.now(clock.withZone(INDIA))));
        log.warn("Admin notice [{}]", kind);
        TelegramApi api = telegram.getIfAvailable();
        if (api == null || adminChatId == null) {
            return;
        }
        try {
            api.sendMessage(adminChatId, "⚠️ " + html, null);
        } catch (TelegramException e) {
            log.warn("Could not notify the admin: {}", e.getMessage());
        }
    }
}
