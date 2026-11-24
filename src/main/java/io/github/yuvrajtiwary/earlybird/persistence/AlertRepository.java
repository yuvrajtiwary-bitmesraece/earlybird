package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an alert. Returns false if this user already has an alert for this posting. That makes
     * re-running the matcher (redelivered event, retried tick) harmless: the second attempt is a no-op.
     */
    public boolean insertIfAbsent(Alert a) {
        return jdbc.sql("""
                        insert into alerts (id, user_id, posting_id, score, matched_skills, missing_skills, mode, status,
                            created_at, sent_at, telegram_message_id, feedback, feedback_at)
                        values (:id, :userId, :postingId, :score, :matchedSkills, :missingSkills, :mode, :status,
                            :createdAt, :sentAt, :telegramMessageId, :feedback, :feedbackAt)
                        on conflict (user_id, posting_id) do nothing
                        """)
                .param("id", a.id())
                .param("userId", a.userId())
                .param("postingId", a.postingId())
                .param("score", a.score())
                .param("matchedSkills", Sql.textArray(a.matchedSkills()))
                .param("missingSkills", Sql.textArray(a.missingSkills()))
                .param("mode", Sql.db(a.mode()))
                .param("status", Sql.db(a.status()))
                .param("createdAt", Sql.ts(a.createdAt()))
                .param("sentAt", Sql.ts(a.sentAt()))
                .param("telegramMessageId", a.telegramMessageId())
                .param("feedback", Sql.db(a.feedback()))
                .param("feedbackAt", Sql.ts(a.feedbackAt()))
                .update() == 1;
    }

    public Optional<Alert> findById(UUID id) {
        return jdbc.sql("select * from alerts where id = :id").param("id", id).query(AlertRepository::map).optional();
    }

    static Alert map(ResultSet rs, int row) throws SQLException {
        return new Alert(
                rs.getObject("id", UUID.class),
                rs.getLong("user_id"),
                rs.getObject("posting_id", UUID.class),
                rs.getInt("score"),
                Sql.list(rs, "matched_skills"),
                Sql.list(rs, "missing_skills"),
                Sql.enumOf(Alert.Mode.class, rs.getString("mode")),
                Sql.enumOf(Alert.Status.class, rs.getString("status")),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "sent_at"),
                Sql.longOrNull(rs, "telegram_message_id"),
                Sql.enumOf(Alert.Feedback.class, rs.getString("feedback")),
                Sql.instant(rs, "feedback_at"));
    }
}
