package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;

/** A row of {@code users}: one Telegram user. Deleting it cascades to profile, session and alerts. */
public record User(
        long id,
        long telegramUserId,
        long chatId,
        String firstName,
        String username,
        Status status,
        Instant consentAt,
        Instant createdAt,
        Instant lastActiveAt) {

    public enum Status { ONBOARDING, ACTIVE, PAUSED, BLOCKED }
}
