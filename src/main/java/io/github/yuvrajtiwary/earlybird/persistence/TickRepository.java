package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TickRepository {

    public enum Status { RUNNING, OK, PARTIAL, FAILED }

    public record Tick(
            long id,
            Instant startedAt,
            Instant finishedAt,
            int boardsChecked,
            int boardsFailed,
            int newPostings,
            int closedPostings,
            Status status) {}

    private final JdbcClient jdbc;

    public TickRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the new tick's id */
    public long start(Instant startedAt) {
        return jdbc.sql("insert into ticks (started_at) values (:startedAt) returning id")
                .param("startedAt", Sql.ts(startedAt))
                .query(Long.class)
                .single();
    }

    public void finish(long id, Instant finishedAt, int boardsChecked, int boardsFailed, int newPostings,
            int closedPostings, Status status) {
        jdbc.sql("""
                        update ticks set finished_at = :finishedAt, boards_checked = :checked, boards_failed = :failed,
                            new_postings = :newPostings, closed_postings = :closed, status = :status
                        where id = :id
                        """)
                .param("finishedAt", Sql.ts(finishedAt))
                .param("checked", boardsChecked)
                .param("failed", boardsFailed)
                .param("newPostings", newPostings)
                .param("closed", closedPostings)
                .param("status", Sql.db(status))
                .param("id", id)
                .update();
    }

    public Optional<Tick> findById(long id) {
        return jdbc.sql("select * from ticks where id = :id")
                .param("id", id)
                .query((rs, row) -> new Tick(
                        rs.getLong("id"),
                        Sql.instant(rs, "started_at"),
                        Sql.instant(rs, "finished_at"),
                        rs.getInt("boards_checked"),
                        rs.getInt("boards_failed"),
                        rs.getInt("new_postings"),
                        rs.getInt("closed_postings"),
                        Sql.enumOf(Status.class, rs.getString("status"))))
                .optional();
    }
}
