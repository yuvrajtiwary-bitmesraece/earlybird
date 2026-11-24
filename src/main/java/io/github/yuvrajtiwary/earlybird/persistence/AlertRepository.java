package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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

    /** What {@code /stats} shows: one user's alerts since {@code since}. */
    public record UserStats(int total, int instant, int digest, int sent, int relevant, int notRelevant,
            String mostMissingSkill) {}

    public UserStats statsForUser(long userId, Instant since) {
        UserStats counts = jdbc.sql("""
                        select count(*) as total,
                               count(*) filter (where mode = 'instant') as instant,
                               count(*) filter (where mode = 'digest') as digest,
                               count(*) filter (where status = 'sent') as sent,
                               count(*) filter (where feedback = 'relevant') as relevant,
                               count(*) filter (where feedback = 'not_relevant') as not_relevant
                        from alerts where user_id = :u and created_at >= :since
                        """)
                .param("u", userId)
                .param("since", Sql.ts(since))
                .query((rs, row) -> new UserStats(rs.getInt("total"), rs.getInt("instant"), rs.getInt("digest"),
                        rs.getInt("sent"), rs.getInt("relevant"), rs.getInt("not_relevant"), null))
                .single();
        String missing = jdbc.sql("""
                        select skill from alerts, unnest(missing_skills) as skill
                        where user_id = :u and created_at >= :since
                        group by skill order by count(*) desc, skill limit 1
                        """)
                .param("u", userId)
                .param("since", Sql.ts(since))
                .query(String.class)
                .optional()
                .orElse(null);
        return new UserStats(counts.total(), counts.instant(), counts.digest(), counts.sent(), counts.relevant(),
                counts.notRelevant(), missing);
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
