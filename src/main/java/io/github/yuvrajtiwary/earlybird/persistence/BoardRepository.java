package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BoardRepository {

    private final JdbcClient jdbc;

    public BoardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds a board unless (source, token) already exists. Safe to run on every startup (seed loading). */
    public boolean insertIfAbsent(Source source, String token, String company) {
        return jdbc.sql("""
                        insert into boards (source, token, company)
                        values (:source, :token, :company)
                        on conflict (source, token) do nothing
                        """)
                .param("source", Sql.db(source))
                .param("token", token)
                .param("company", company)
                .update() == 1;
    }

    public Optional<Board> findById(long id) {
        return jdbc.sql("select * from boards where id = :id").param("id", id).query(BoardRepository::map).optional();
    }

    public Optional<Board> find(Source source, String token) {
        return jdbc.sql("select * from boards where source = :source and token = :token")
                .param("source", Sql.db(source))
                .param("token", token)
                .query(BoardRepository::map)
                .optional();
    }

    public List<Board> findEnabled() {
        return jdbc.sql("select * from boards where enabled order by id").query(BoardRepository::map).list();
    }

    static Board map(ResultSet rs, int row) throws SQLException {
        return new Board(
                rs.getLong("id"),
                Sql.enumOf(Source.class, rs.getString("source")),
                rs.getString("token"),
                rs.getString("company"),
                rs.getBoolean("enabled"),
                rs.getInt("check_interval_s"),
                Sql.instant(rs, "next_check_at"),
                Sql.instant(rs, "last_checked_at"),
                Sql.instant(rs, "last_success_at"),
                rs.getInt("consecutive_failures"),
                rs.getString("last_error"),
                Sql.intOrNull(rs, "last_job_count"),
                Sql.instant(rs, "created_at"));
    }
}
