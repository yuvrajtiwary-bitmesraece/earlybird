package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    // ------------------------------------------------------------------------------- delivery (notifier)

    /** Everything the notifier needs to send one alert, in one query. */
    public record Delivery(
            Alert alert, long chatId, User.Status userStatus, int dailyCap, String title, String company, String url,
            String locationRaw, Instant firstSeenAt, Instant closedAt, List<String> cities, String workMode,
            Double minYears, Double maxYears, List<String> jobSkills) {}

    public Optional<Delivery> findDelivery(UUID alertId) {
        return jdbc.sql("""
                        select a.*, u.chat_id, u.status as user_status, coalesce(pr.daily_cap, 10) as cap,
                               p.title, p.company, p.url, p.location_raw, p.first_seen_at, p.closed_at,
                               t.cities, t.work_mode, t.min_years, t.max_years, t.skills as job_skills
                        from alerts a
                        join users u on u.id = a.user_id
                        left join profiles pr on pr.user_id = a.user_id
                        join postings p on p.id = a.posting_id
                        left join posting_tags t on t.posting_id = a.posting_id
                        where a.id = :id
                        """)
                .param("id", alertId)
                .query((rs, row) -> new Delivery(map(rs, row), rs.getLong("chat_id"),
                        Sql.enumOf(User.Status.class, rs.getString("user_status")), rs.getInt("cap"),
                        rs.getString("title"), rs.getString("company"), rs.getString("url"), rs.getString("location_raw"),
                        Sql.instant(rs, "first_seen_at"), Sql.instant(rs, "closed_at"), Sql.list(rs, "cities"),
                        rs.getString("work_mode"), Sql.dbl(rs, "min_years"), Sql.dbl(rs, "max_years"),
                        Sql.list(rs, "job_skills")))
                .optional();
    }

    /**
     * {@code queued → sending}, only if it is still queued. Two deliveries of the same AlertRequested can't
     * both send: the second finds nothing to claim.
     */
    public boolean claimForSending(UUID id) {
        return jdbc.sql("update alerts set status = 'sending' where id = :id and status = 'queued'")
                .param("id", id).update() == 1;
    }

    public void markSent(UUID id, Instant sentAt, Long telegramMessageId) {
        jdbc.sql("update alerts set status = 'sent', sent_at = :at, telegram_message_id = :msg where id = :id")
                .param("at", Sql.ts(sentAt)).param("msg", telegramMessageId).param("id", id).update();
    }

    public void markStatus(UUID id, Alert.Status status) {
        jdbc.sql("update alerts set status = :s where id = :id").param("s", Sql.db(status)).param("id", id).update();
    }

    /** Over the daily cap: the alert waits for the 20:00 digest instead. */
    public void moveToDigest(UUID id) {
        jdbc.sql("update alerts set mode = 'digest', status = 'queued' where id = :id and status in ('queued', 'sending')")
                .param("id", id).update();
    }

    /** The posting closed: alerts not yet sent are cancelled (PostingClosed). */
    public int cancelQueuedForPosting(UUID postingId) {
        return jdbc.sql("update alerts set status = 'cancelled' where posting_id = :p and status = 'queued'")
                .param("p", postingId).update();
    }

    /** The daily-cap fallback when Redis is down: instant alerts already sent (or being sent) today. */
    public int countInstantSentSince(long userId, Instant since) {
        return jdbc.sql("""
                        select count(*) from alerts
                        where user_id = :u and mode = 'instant' and status in ('sending', 'sent') and created_at >= :since
                        """)
                .param("u", userId).param("since", Sql.ts(since)).query(Integer.class).single();
    }

    /** 👍/👎 from the alert's buttons. Only the alert's own user can rate it. */
    public boolean setFeedback(UUID id, long userId, Alert.Feedback feedback, Instant at) {
        return jdbc.sql("update alerts set feedback = :f, feedback_at = :at where id = :id and user_id = :u")
                .param("f", Sql.db(feedback)).param("at", Sql.ts(at)).param("id", id).param("u", userId)
                .update() == 1;
    }

    // ------------------------------------------------------------------------------- digest

    public record DigestItem(UUID alertId, long userId, long chatId, String title, String company, String url, int score) {}

    /** Queued digest alerts since {@code since}, for active users and open postings, best first per user. */
    public List<DigestItem> digestQueue(Instant since) {
        return jdbc.sql("""
                        select a.id, a.user_id, u.chat_id, p.title, p.company, p.url, a.score
                        from alerts a
                        join users u on u.id = a.user_id and u.status = 'active'
                        join postings p on p.id = a.posting_id and p.closed_at is null
                        where a.mode = 'digest' and a.status = 'queued' and a.created_at >= :since
                        order by a.user_id, a.score desc, a.created_at
                        """)
                .param("since", Sql.ts(since))
                .query((rs, row) -> new DigestItem(rs.getObject("id", UUID.class), rs.getLong("user_id"),
                        rs.getLong("chat_id"), rs.getString("title"), rs.getString("company"), rs.getString("url"),
                        rs.getInt("score")))
                .list();
    }

    public void markSentMany(Collection<UUID> ids, Instant at) {
        if (!ids.isEmpty()) {
            jdbc.sql("update alerts set status = 'sent', sent_at = :at where id = any(cast(:ids as uuid[]))")
                    .param("at", Sql.ts(at)).param("ids", ids.stream().map(UUID::toString).toArray(String[]::new)).update();
        }
    }

    /** Digest alerts that weren't sent (beyond the top 10, older than a day, closed posting) are dropped. */
    public int skipUnsentDigests(Instant before) {
        return jdbc.sql("""
                        update alerts set status = 'skipped'
                        where mode = 'digest' and status = 'queued' and created_at < :before
                        """)
                .param("before", Sql.ts(before)).update();
    }

    public void markSkippedMany(Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("update alerts set status = 'skipped' where id = any(cast(:ids as uuid[]))")
                    .param("ids", ids.stream().map(UUID::toString).toArray(String[]::new)).update();
        }
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
