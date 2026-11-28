package io.github.yuvrajtiwary.earlybird.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.common.TokenBucketRateLimiter;
import io.github.yuvrajtiwary.earlybird.events.DeadLetterException;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.Delivery;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.telegram.api.Message;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every branch of a delivery (spec 3.10), with the database and Telegram faked. */
class NotifierTest {

    private final AlertRepository alerts = mock(AlertRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TelegramApi telegram = mock(TelegramApi.class);
    private final DailyCaps caps = mock(DailyCaps.class);
    private final Notifier notifier = new Notifier(alerts, users, telegram, new TokenBucketRateLimiter(Clock.systemUTC()),
            caps, new AlertFormatter("", "https://eb.example/logos/", java.util.Set.of("acme.com")), mock(EventBus.class), Clock.systemUTC(), new SimpleMeterRegistry(),
            List.of(Duration.ZERO));

    private final UUID id = Ids.newId();

    private void given(Alert.Status status, User.Status userStatus, Instant closedAt) {
        Alert alert = new Alert(id, 7, Ids.newId(), 80, List.of("Java"), List.of(), Alert.Mode.INSTANT, status,
                Instant.now(), null, null, null, null);
        when(alerts.findDelivery(id)).thenReturn(Optional.of(new Delivery(alert, 42, userStatus, 10, "Backend Engineer",
                "Acme", "https://x", "Bengaluru", Instant.now(), closedAt, List.of(), "onsite", null, null, List.of("Java"), "acme.com")));
        when(caps.tryReserve(anyLong(), eq(10))).thenReturn(true);
        when(alerts.claimForSending(id)).thenReturn(true);
    }

    private static Message sent() {
        return new Message(555, null, null, 1L, null, null);
    }

    @Test
    void sendsAndRecordsTheTelegramMessage() {
        given(Alert.Status.QUEUED, User.Status.ACTIVE, null);
        when(telegram.sendMessage(eq(42L), anyString(), any(), any())).thenReturn(sent());

        assertThat(notifier.deliver(id, false)).isEqualTo(Alert.Status.SENT);
        verify(alerts).markSent(eq(id), any(), eq(555L));
        verify(telegram).sendMessage(eq(42L), anyString(), any(), eq("https://eb.example/logos/acme.com.png?v=1")); // issue #10
    }

    @Test
    void anAlertThatIsNoLongerQueuedIsNotSentAgain() {
        given(Alert.Status.SENT, User.Status.ACTIVE, null);

        assertThat(notifier.deliver(id, false)).isEqualTo(Alert.Status.SENT);
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void losingTheClaimMeansSomeoneElseIsSendingIt() {
        given(Alert.Status.QUEUED, User.Status.ACTIVE, null);
        when(alerts.claimForSending(id)).thenReturn(false);

        notifier.deliver(id, false);
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any(), any());
        // A losing copy must not use up the daily cap or pull the winner's alert back into the digest.
        verify(caps, never()).tryReserve(anyLong(), anyInt());
        verify(alerts, never()).moveToDigest(any());
    }

    @Test
    void pausedUsersAndClosedPostingsAreSkipped() {
        given(Alert.Status.QUEUED, User.Status.PAUSED, null);
        assertThat(notifier.deliver(id, false)).isEqualTo(Alert.Status.SKIPPED);

        given(Alert.Status.QUEUED, User.Status.ACTIVE, Instant.now());
        assertThat(notifier.deliver(id, false)).isEqualTo(Alert.Status.CANCELLED);
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void overTheDailyCapItWaitsForTheDigest() {
        given(Alert.Status.QUEUED, User.Status.ACTIVE, null);
        when(caps.tryReserve(anyLong(), eq(10))).thenReturn(false);

        notifier.deliver(id, false);
        var order = inOrder(alerts, caps);
        order.verify(alerts).claimForSending(id); // only the copy that owns the alert moves it
        order.verify(caps).tryReserve(anyLong(), eq(10));
        order.verify(alerts).moveToDigest(id);
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void aUserWhoBlockedTheBotIsMarkedBlocked() {
        given(Alert.Status.QUEUED, User.Status.ACTIVE, null);
        when(telegram.sendMessage(eq(42L), anyString(), any(), any()))
                .thenThrow(new TelegramException(403, "Forbidden: bot was blocked by the user", null));

        assertThat(notifier.deliver(id, false)).isEqualTo(Alert.Status.FAILED);
        verify(users).updateStatus(7, User.Status.BLOCKED);
    }

    @Test
    void rateLimitsAndServerErrorsAreRetried() {
        given(Alert.Status.QUEUED, User.Status.ACTIVE, null);
        when(telegram.sendMessage(eq(42L), anyString(), any(), any()))
                .thenThrow(new TelegramException(429, "Too Many Requests", Duration.ofMillis(10)))
                .thenThrow(new TelegramException(502, "Bad Gateway", null))
                .thenReturn(sent());

        assertThat(notifier.deliver(id, false)).isEqualTo(Alert.Status.SENT);
        verify(telegram, times(3)).sendMessage(eq(42L), anyString(), any(), any());
    }

    /** Spec 3.10: "other 4xx: mark failed and send to the DLQ". The dead letter is what tells the admin. */
    @Test
    void otherClientErrorsFailTheAlertAndDeadLetterTheEvent() {
        given(Alert.Status.QUEUED, User.Status.ACTIVE, null);
        when(telegram.sendMessage(eq(42L), anyString(), any(), any()))
                .thenThrow(new TelegramException(400, "Bad Request: can't parse entities", null));

        assertThatThrownBy(() -> notifier.deliver(id, false))
                .isInstanceOf(DeadLetterException.class)
                .hasMessageContaining("Bad Request: can't parse entities");
        verify(alerts).markStatus(id, Alert.Status.FAILED);
        verify(telegram, times(1)).sendMessage(eq(42L), anyString(), any(), any()); // not retried
    }

    @Test
    void givesUpAfterThreeFailedAttempts() {
        given(Alert.Status.QUEUED, User.Status.ACTIVE, null);
        when(telegram.sendMessage(eq(42L), anyString(), any(), any())).thenThrow(new TelegramException(0, "network error", null));

        assertThat(notifier.deliver(id, false)).isEqualTo(Alert.Status.FAILED);
        verify(telegram, times(Notifier.MAX_ATTEMPTS)).sendMessage(eq(42L), anyString(), any(), any());
    }
}
