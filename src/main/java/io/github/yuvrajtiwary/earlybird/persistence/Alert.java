package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A row of {@code alerts}: one posting matched to one user. At most one per (user, posting). */
public record Alert(
        UUID id,
        long userId,
        UUID postingId,
        int score,
        List<String> matchedSkills,
        List<String> missingSkills,
        Mode mode,
        Status status,
        Instant createdAt,
        Instant sentAt,
        Long telegramMessageId,
        Feedback feedback,
        Instant feedbackAt) {

    public Alert {
        matchedSkills = matchedSkills == null ? List.of() : List.copyOf(matchedSkills);
        missingSkills = missingSkills == null ? List.of() : List.copyOf(missingSkills);
    }

    public enum Mode { INSTANT, DIGEST }

    /** {@code queued → sending → sent | failed | skipped | cancelled}. */
    public enum Status { QUEUED, SENDING, SENT, FAILED, SKIPPED, CANCELLED }

    public enum Feedback { RELEVANT, NOT_RELEVANT }
}
