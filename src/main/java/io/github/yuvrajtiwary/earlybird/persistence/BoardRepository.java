package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BoardRepository {

    private static final int MAX_ERROR_LENGTH = 500;

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

    /**
     * Sets the company's website domain (for its logo) when it differs from what's stored. Run with seed loading,
     * so a domain added or fixed in the seed file reaches boards that already exist.
     */
    public boolean updateDomain(Source source, String token, String domain) {
        return jdbc.sql("""
                        update boards set domain = cast(:domain as text)
                        where source = :source and token = :token and domain is distinct from cast(:domain as text)
                        """)
                .param("source", Sql.db(source))
                .param("token", token)
                .param("domain", domain)
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

    /** Boards that may fail this many checks in a row before they are switched off (spec section 8). */
    public static final int DISABLE_AFTER_FAILURES = 20;
    /** However often a board fails, it's retried at least hourly until it's disabled. */
    public static final int MAX_BACKOFF_SECONDS = 3600;

    /**
     * Enabled boards whose next check is due. A board checked at 10:00:05 is due again at 10:03:05, so up to 30 s
     * early counts as due: otherwise a tick at 10:03:00 would skip it and a 3-minute board would be checked every 6.
     */
    public List<Board> findDue(Instant now) {
        return jdbc.sql("""
                        select * from boards
                        where enabled and (next_check_at is null or next_check_at <= :soon)
                        order by id
                        """)
                .param("soon", Sql.ts(now.plusSeconds(30)))
                .query(BoardRepository::map)
                .list();
    }

    public List<Board> findDisabled() {
        return jdbc.sql("select * from boards where not enabled order by id").query(BoardRepository::map).list();
    }

    /** A disabled board answered again: back to normal checks from the next tick. */
    public void reEnable(long id, Instant now) {
        jdbc.sql("""
                        update boards set enabled = true, consecutive_failures = 0, consecutive_empty_checks = 0,
                            last_error = null, next_check_at = :now
                        where id = :id
                        """)
                .param("now", Sql.ts(now)).param("id", id).update();
    }

    public List<Board> findEnabled() {
        return jdbc.sql("select * from boards where enabled order by id").query(BoardRepository::map).list();
    }

    /** A full, believed response: resets the failure counters and remembers the job count and ETag. */
    public void recordSuccess(long id, Instant now, int jobCount, String etag) {
        jdbc.sql("""
                        update boards set last_checked_at = :now, last_success_at = :now, consecutive_failures = 0,
                            consecutive_empty_checks = 0, last_error = null, last_job_count = :jobCount, etag = :etag,
                            next_check_at = :now + make_interval(secs => check_interval_s)
                        where id = :id
                        """)
                .param("now", Sql.ts(now))
                .param("jobCount", jobCount)
                .param("etag", etag)
                .param("id", id)
                .update();
    }

    /** 304 Not Modified: the board answered, nothing changed. */
    public void recordNotModified(long id, Instant now) {
        jdbc.sql("""
                        update boards set last_checked_at = :now, last_success_at = :now, consecutive_failures = 0,
                            consecutive_empty_checks = 0, last_error = null,
                            next_check_at = :now + make_interval(secs => check_interval_s)
                        where id = :id
                        """)
                .param("now", Sql.ts(now))
                .param("id", id)
                .update();
    }

    /**
     * The check failed (HTTP error, timeout, unreadable response). Backoff: the n-th failure in a row waits
     * interval × 2^(n-1), at most an hour (3, 6, 12, 24, 48, 60 min…); the 20th in a row disables the board.
     *
     * @return true if this failure disabled the board
     */
    public boolean recordFailure(long id, Instant now, String error) {
        return jdbc.sql("""
                        update boards set last_checked_at = :now, consecutive_failures = consecutive_failures + 1,
                            last_error = :error, next_check_at = :now + make_interval(secs => least(:maxBackoff,
                                check_interval_s * power(2, least(consecutive_failures, 10))::int)),
                            enabled = consecutive_failures + 1 < :disableAfter
                        where id = :id
                        returning not enabled
                        """)
                .param("now", Sql.ts(now))
                .param("error", truncate(error))
                .param("maxBackoff", MAX_BACKOFF_SECONDS)
                .param("disableAfter", DISABLE_AFTER_FAILURES)
                .param("id", id)
                .query(Boolean.class)
                .optional().orElse(false);
    }

    /**
     * A board that had jobs answered "0 jobs": counted as a failure (same backoff) and ignored until it repeats
     * 3 times.
     *
     * @return true if this disabled the board
     */
    public boolean recordSuspiciousEmpty(long id, Instant now, String error) {
        return jdbc.sql("""
                        update boards set last_checked_at = :now, consecutive_failures = consecutive_failures + 1,
                            consecutive_empty_checks = consecutive_empty_checks + 1, last_error = :error,
                            next_check_at = :now + make_interval(secs => least(:maxBackoff,
                                check_interval_s * power(2, least(consecutive_failures, 10))::int)),
                            enabled = consecutive_failures + 1 < :disableAfter
                        where id = :id
                        returning not enabled
                        """)
                .param("now", Sql.ts(now))
                .param("error", truncate(error))
                .param("maxBackoff", MAX_BACKOFF_SECONDS)
                .param("disableAfter", DISABLE_AFTER_FAILURES)
                .param("id", id)
                .query(Boolean.class)
                .optional().orElse(false);
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
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
                Sql.instant(rs, "created_at"),
                rs.getInt("consecutive_empty_checks"),
                rs.getString("etag"));
    }
}
