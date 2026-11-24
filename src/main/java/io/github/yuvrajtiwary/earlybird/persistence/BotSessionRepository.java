package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BotSessionRepository {

    private final JdbcClient jdbc;

    public BotSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves the user's current flow state; {@code dataJson} must be a JSON object. */
    public void save(long userId, String state, String dataJson, Instant now) {
        jdbc.sql("""
                        insert into bot_sessions (user_id, state, data, updated_at)
                        values (:userId, :state, cast(:data as jsonb), :now)
                        on conflict (user_id) do update set
                            state = excluded.state, data = excluded.data, updated_at = excluded.updated_at
                        """)
                .param("userId", userId)
                .param("state", state)
                .param("data", dataJson)
                .param("now", Sql.ts(now))
                .update();
    }

    public Optional<BotSession> find(long userId) {
        return jdbc.sql("select user_id, state, data::text as data, updated_at from bot_sessions where user_id = :id")
                .param("id", userId)
                .query((rs, row) -> new BotSession(
                        rs.getLong("user_id"), rs.getString("state"), rs.getString("data"),
                        Sql.instant(rs, "updated_at")))
                .optional();
    }

    public boolean delete(long userId) {
        return jdbc.sql("delete from bot_sessions where user_id = :id").param("id", userId).update() == 1;
    }
}
