package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import java.util.List;
import java.util.UUID;

/**
 * Send this alert now (topic {@code eb.alerts.requested.v1}, key = userId, so one user's alerts arrive in
 * order). Spec 3.2, plus {@code recent}: true for the "recent matches" sent right after onboarding or a
 * preference change, which are labelled differently.
 */
public record AlertRequested(
        UUID alertId, long userId, UUID postingId, int score, List<String> matchedSkills, List<String> missingSkills,
        boolean recent) {

    public static AlertRequested of(Alert a, boolean recent) {
        return new AlertRequested(a.id(), a.userId(), a.postingId(), a.score(), a.matchedSkills(), a.missingSkills(),
                recent);
    }
}
