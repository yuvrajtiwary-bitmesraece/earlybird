package io.github.yuvrajtiwary.earlybird.alerts;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.DigestItem;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 20:00 IST digest (spec 3.10): for each user, one message with up to 10 digest alerts from the last 24
 * hours, best first. Those are marked sent; the rest (beyond 10, or older) are skipped so they don't pile up.
 */
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    static final int MAX_ITEMS = 10;

    private final AlertRepository alerts;
    private final UserRepository users;
    private final TelegramApi telegram;
    private final RateLimiter perChat;
    private final AlertFormatter formatter;
    private final Clock clock;

    public DigestService(AlertRepository alerts, UserRepository users, TelegramApi telegram, RateLimiter perChat,
            AlertFormatter formatter, Clock clock) {
        this.alerts = alerts;
        this.users = users;
        this.telegram = telegram;
        this.perChat = perChat;
        this.formatter = formatter;
        this.clock = clock;
    }

    /** @return the number of users who got a digest */
    public int sendAll() {
        Instant since = clock.instant().minus(Duration.ofHours(24));
        Map<Long, List<DigestItem>> byUser = alerts.digestQueue(since).stream()
                .collect(Collectors.groupingBy(DigestItem::userId, LinkedHashMap::new, Collectors.toList()));
        int sent = 0;
        for (List<DigestItem> items : byUser.values()) {
            List<DigestItem> top = items.subList(0, Math.min(MAX_ITEMS, items.size()));
            List<UUID> rest = items.subList(top.size(), items.size()).stream().map(DigestItem::alertId).toList();
            long chatId = top.getFirst().chatId();
            try {
                perChat.acquire("chat:" + chatId, Notifier.PER_CHAT);
                telegram.sendMessage(chatId, formatter.digest(top), null);
                alerts.markSentMany(top.stream().map(DigestItem::alertId).toList(), clock.instant());
                alerts.markSkippedMany(rest);
                sent++;
            } catch (TelegramException e) {
                if (e.blockedByUser()) {
                    users.updateStatus(top.getFirst().userId(), User.Status.BLOCKED);
                }
                log.warn("Digest for user {} failed: {}", top.getFirst().userId(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int dropped = alerts.skipUnsentDigests(since);
        log.info("Digest: sent to {} users, {} stale digest alerts dropped", sent, dropped);
        return sent;
    }
}
