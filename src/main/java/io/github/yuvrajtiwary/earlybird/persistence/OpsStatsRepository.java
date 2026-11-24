package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Small counts for the admin's {@code /admin} view (and later the /status page). */
@Repository
public class OpsStatsRepository {

    public record BoardHealth(int enabled, int failing) {}

    private final JdbcClient jdbc;

    public OpsStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TickRepository.Tick> latestTick() {
        return jdbc.sql("select * from ticks order by id desc limit 1")
                .query((rs, row) -> new TickRepository.Tick(
                        rs.getLong("id"), Sql.instant(rs, "started_at"), Sql.instant(rs, "finished_at"),
                        rs.getInt("boards_checked"), rs.getInt("boards_failed"), rs.getInt("new_postings"),
                        rs.getInt("closed_postings"),
                        Sql.enumOf(TickRepository.Status.class, rs.getString("status"))))
                .optional();
    }

    public BoardHealth boardHealth() {
        return jdbc.sql("""
                        select count(*) filter (where enabled) as enabled,
                               count(*) filter (where enabled and consecutive_failures > 0) as failing
                        from boards
                        """)
                .query((rs, row) -> new BoardHealth(rs.getInt("enabled"), rs.getInt("failing")))
                .single();
    }

    /** New (non-baseline) postings first seen since {@code since}. */
    public int newPostingsSince(Instant since) {
        return jdbc.sql("select count(*) from postings where first_seen_at >= :since and not baseline")
                .param("since", Sql.ts(since))
                .query(Integer.class)
                .single();
    }

    public int alertsSince(Instant since) {
        return jdbc.sql("select count(*) from alerts where created_at >= :since")
                .param("since", Sql.ts(since))
                .query(Integer.class)
                .single();
    }

    public int activeUsers() {
        return jdbc.sql("select count(*) from users where status = 'active'").query(Integer.class).single();
    }
}
