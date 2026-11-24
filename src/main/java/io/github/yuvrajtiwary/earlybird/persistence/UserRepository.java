package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a user in {@code onboarding} status. Fails with DuplicateKeyException if the Telegram user exists. */
    public User create(long telegramUserId, long chatId, String firstName, String username, Instant now) {
        return jdbc.sql("""
                        insert into users (telegram_user_id, chat_id, first_name, username, created_at, last_active_at)
                        values (:telegramUserId, :chatId, :firstName, :username, :now, :now)
                        returning *
                        """)
                .param("telegramUserId", telegramUserId)
                .param("chatId", chatId)
                .param("firstName", firstName)
                .param("username", username)
                .param("now", Sql.ts(now))
                .query(UserRepository::map)
                .single();
    }

    public Optional<User> findById(long id) {
        return jdbc.sql("select * from users where id = :id").param("id", id).query(UserRepository::map).optional();
    }

    public Optional<User> findByTelegramUserId(long telegramUserId) {
        return jdbc.sql("select * from users where telegram_user_id = :tg")
                .param("tg", telegramUserId)
                .query(UserRepository::map)
                .optional();
    }

    public boolean updateStatus(long id, User.Status status) {
        return jdbc.sql("update users set status = :status where id = :id")
                .param("status", Sql.db(status))
                .param("id", id)
                .update() == 1;
    }

    /** Onboarding finished: alerts on, and the moment the user agreed to the privacy note is recorded once. */
    public void activate(long id, Instant now) {
        jdbc.sql("update users set status = 'active', consent_at = coalesce(consent_at, :now) where id = :id")
                .param("now", Sql.ts(now))
                .param("id", id)
                .update();
    }

    /** Called on every message, so /admin can tell active users from dormant ones. */
    public void touch(long id, Instant now) {
        jdbc.sql("update users set last_active_at = :now where id = :id")
                .param("now", Sql.ts(now))
                .param("id", id)
                .update();
    }

    /** Hard delete. The foreign keys cascade to the profile, bot session and alerts ({@code /delete}). */
    public boolean delete(long id) {
        return jdbc.sql("delete from users where id = :id").param("id", id).update() == 1;
    }

    static User map(ResultSet rs, int row) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getLong("telegram_user_id"),
                rs.getLong("chat_id"),
                rs.getString("first_name"),
                rs.getString("username"),
                Sql.enumOf(User.Status.class, rs.getString("status")),
                Sql.instant(rs, "consent_at"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "last_active_at"));
    }
}
