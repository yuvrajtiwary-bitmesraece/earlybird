package io.github.yuvrajtiwary.earlybird.alerts;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.common.RateLimiter.Bandwidth;
import io.github.yuvrajtiwary.earlybird.events.AlertRequested;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.events.EventEnvelope;
import io.github.yuvrajtiwary.earlybird.events.Topic;
import io.github.yuvrajtiwary.earlybird.ops.AdminNotifier;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.Delivery;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.telegram.api.Message;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * The notifier (spec 3.10). Per AlertRequested (keyed by user, so one user's alerts arrive in order):
 * <ol>
 *   <li>skip if the alert isn't queued any more, the user isn't active, or the posting closed;
 *   <li>count it against the user's daily cap; over the cap it moves to the 20:00 digest;
 *   <li>claim it ({@code queued → sending}): only one delivery can win, so it's never sent twice;
 *   <li>pace: 25 messages/s overall (inside the Telegram client) and 1 per second per chat;
 *   <li>send, then {@code sent}. 429 waits {@code retry_after}; 403 means the user blocked us; 5xx and network
 *       errors are retried; any other 4xx fails the alert.
 * </ol>
 */
public class Notifier implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    static final String CONSUMER = "eb-notifier";
    static final Bandwidth PER_CHAT = Bandwidth.perSecond(1);
    static final int MAX_ATTEMPTS = 3;

    private final AlertRepository alerts;
    private final UserRepository users;
    private final TelegramApi telegram;
    private final RateLimiter perChat;
    private final DailyCaps caps;
    private final AlertFormatter formatter;
    private final EventBus bus;
    private final AdminNotifier admin;
    private final Clock clock;
    private final MeterRegistry meters;
    private final List<Duration> backoff;
    private final Timer deliveryLag;

    public Notifier(AlertRepository alerts, UserRepository users, TelegramApi telegram, RateLimiter perChat,
            DailyCaps caps, AlertFormatter formatter, EventBus bus, AdminNotifier admin, Clock clock,
            MeterRegistry meters, List<Duration> backoff) {
        this.alerts = alerts;
        this.users = users;
        this.telegram = telegram;
        this.perChat = perChat;
        this.caps = caps;
        this.formatter = formatter;
        this.bus = bus;
        this.admin = admin;
        this.clock = clock;
        this.meters = meters;
        this.backoff = List.copyOf(backoff);
        // first_seen → sent: the spec's "delivery lag" SLO (p95 ≤ 60 s), as a histogram for percentiles.
        this.deliveryLag = Timer.builder("earlybird.alert.delivery").publishPercentileHistogram()
                .description("Time from first seeing a posting to the alert being sent").register(meters);
    }

    @Override
    public void afterPropertiesSet() {
        bus.subscribe(Topic.ALERTS_REQUESTED, CONSUMER, AlertRequested.class, this::onRequested);
    }

    private void onRequested(EventEnvelope<AlertRequested> event) {
        deliver(event.payload().alertId(), event.payload().recent());
    }

    /** @return what happened, for tests and logs */
    public Alert.Status deliver(UUID alertId, boolean recent) {
        Optional<Delivery> found = alerts.findDelivery(alertId);
        if (found.isEmpty()) {
            return null;
        }
        Delivery d = found.get();
        Alert alert = d.alert();
        if (alert.status() != Alert.Status.QUEUED) {
            return alert.status(); // already handled (redelivery)
        }
        if (d.userStatus() != User.Status.ACTIVE) {
            return finish(alertId, Alert.Status.SKIPPED, "user_inactive");
        }
        if (d.closedAt() != null) {
            return finish(alertId, Alert.Status.CANCELLED, "posting_closed");
        }
        // Claim before touching the cap. Copies of the same event that lose the claim must neither use up the
        // user's daily cap nor move an alert that the winner is already sending back to the digest.
        if (!alerts.claimForSending(alertId)) {
            return Alert.Status.SENDING; // another delivery got there first
        }
        if (alert.mode() == Alert.Mode.INSTANT && !caps.tryReserve(alert.userId(), d.dailyCap())) {
            alerts.moveToDigest(alertId);
            count("over_cap");
            return Alert.Status.QUEUED;
        }
        return send(d, recent);
    }

    private Alert.Status send(Delivery d, boolean recent) {
        UUID id = d.alert().id();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                perChat.acquire("chat:" + d.chatId(), PER_CHAT);
                Instant now = clock.instant();
                Message sent = telegram.sendMessage(d.chatId(), formatter.alert(d, recent, now), formatter.keyboard(d));
                alerts.markSent(id, now, sent == null ? null : sent.messageId());
                deliveryLag.record(Duration.between(d.firstSeenAt(), now));
                count("sent");
                return Alert.Status.SENT;
            } catch (TelegramException e) {
                if (e.blockedByUser()) {
                    users.updateStatus(d.alert().userId(), User.Status.BLOCKED);
                    return finish(id, Alert.Status.FAILED, "blocked");
                }
                boolean retryable = e.errorCode() == 429 || e.errorCode() == 0 || e.errorCode() >= 500;
                if (!retryable) {
                    log.error("Alert {} rejected by Telegram: {}", id, e.getMessage());
                    admin.notifyOncePerDay("alert-rejected", "Telegram rejected an alert: " + e.getMessage());
                    return finish(id, Alert.Status.FAILED, "rejected");
                }
                if (attempt < MAX_ATTEMPTS) {
                    sleep(e.retryAfter() != null ? e.retryAfter() : backoff.get(Math.min(attempt - 1, backoff.size() - 1)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return finish(id, Alert.Status.FAILED, "gave_up");
    }

    private Alert.Status finish(UUID id, Alert.Status status, String result) {
        alerts.markStatus(id, status);
        count(result);
        return status;
    }

    private void count(String result) {
        meters.counter("earlybird.alerts", "mode", "instant", "result", result).increment();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
