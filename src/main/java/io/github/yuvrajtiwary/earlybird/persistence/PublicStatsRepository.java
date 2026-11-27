package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Everything the public website shows (spec 3.12, 3.13), as read-only SQL. Baseline postings (jobs that were
 * already on a board when it was first checked) are never counted as "new". Days are India days.
 *
 * <p>Latency follows spec 3.13 and only counts instant alerts sent within an hour of the posting being found:
 * "recent matches" sent after onboarding are for postings up to two days old, and would make delivery look
 * slow when nothing was.
 */
@Repository
public class PublicStatsRepository {

    /** Instant alerts sent soon after the posting was found: the ones latency is about. */
    private static final String TIMELY_ALERTS = """
            from alerts a join postings p on p.id = a.posting_id
            where a.mode = 'instant' and a.status = 'sent' and a.sent_at >= :since and a.sent_at < :until
              and a.created_at - p.first_seen_at < interval '1 hour'
            """;

    private final JdbcClient jdbc;

    public PublicStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int enabledBoards() {
        return jdbc.sql("select count(*) from boards where enabled").query(Integer.class).single();
    }

    public int newPostingsSince(Instant since) {
        return jdbc.sql("select count(*) from postings where not baseline and first_seen_at >= :since")
                .param("since", Sql.ts(since)).query(Integer.class).single();
    }

    public int alertsSentSince(Instant since) {
        return jdbc.sql("select count(*) from alerts where sent_at >= :since")
                .param("since", Sql.ts(since)).query(Integer.class).single();
    }

    /** p50 and p95 in seconds; null when there's no data. */
    public record Percentiles(Double p50, Double p95, int count) {}

    /** delivery_lag = sent_at − first_seen_at (spec 3.13). */
    public Percentiles deliveryLag(Instant since, Instant until) {
        return percentiles("extract(epoch from a.sent_at - p.first_seen_at)", "", since, until);
    }

    /**
     * published_to_alert = sent_at − source_published_at, for platforms whose publish time is trustworthy
     * (Greenhouse, Ashby: Lever's is null); negative values and over 7 days are ignored (spec 3.13).
     */
    public Percentiles publishedToAlert(Instant since, Instant until) {
        return percentiles("extract(epoch from a.sent_at - p.source_published_at)",
                " and p.source_published_at is not null and a.sent_at >= p.source_published_at"
                        + " and a.sent_at - p.source_published_at <= interval '7 days'", since, until);
    }

    private Percentiles percentiles(String seconds, String extra, Instant since, Instant until) {
        return jdbc.sql("select percentile_cont(0.5) within group (order by " + seconds + ") as p50, "
                        + "percentile_cont(0.95) within group (order by " + seconds + ") as p95, count(*) as n "
                        + TIMELY_ALERTS + extra)
                .param("since", Sql.ts(since)).param("until", Sql.ts(until))
                .query((rs, row) -> new Percentiles(Sql.dbl(rs, "p50"), Sql.dbl(rs, "p95"), rs.getInt("n")))
                .single();
    }

    /** New postings per India day, from {@code since}; days without any are absent. */
    public Map<LocalDate, Integer> postingsPerDay(Instant since) {
        return perDay("select (first_seen_at at time zone 'Asia/Kolkata')::date as d, count(*) as n from postings"
                + " where not baseline and first_seen_at >= :since group by 1", since);
    }

    /** Alerts sent per India day, from {@code since}. */
    public Map<LocalDate, Integer> alertsPerDay(Instant since) {
        return perDay("select (sent_at at time zone 'Asia/Kolkata')::date as d, count(*) as n from alerts"
                + " where sent_at >= :since group by 1", since);
    }

    private Map<LocalDate, Integer> perDay(String sql, Instant since) {
        Map<LocalDate, Integer> out = new TreeMap<>();
        jdbc.sql(sql).param("since", Sql.ts(since))
                .query((rs, row) -> out.put(rs.getObject("d", LocalDate.class), rs.getInt("n"))).list();
        return out;
    }

    public record Relevance(int relevant, int notRelevant) {}

    public Relevance relevance(Instant since, Instant until) {
        return jdbc.sql("""
                        select count(*) filter (where feedback = 'relevant') as r,
                               count(*) filter (where feedback = 'not_relevant') as nr
                        from alerts where feedback_at >= :since and feedback_at < :until
                        """)
                .param("since", Sql.ts(since)).param("until", Sql.ts(until))
                .query((rs, row) -> new Relevance(rs.getInt("r"), rs.getInt("nr"))).single();
    }

    public record Count(String name, int count) {}

    /** Companies with the most new postings since {@code since}. */
    public List<Count> topCompanies(Instant since, Instant until, int limit) {
        return jdbc.sql("""
                        select company as name, count(*) as n from postings
                        where not baseline and not is_repost and first_seen_at >= :since and first_seen_at < :until
                        group by company order by n desc, company limit :limit
                        """)
                .param("since", Sql.ts(since)).param("until", Sql.ts(until)).param("limit", limit)
                .query((rs, row) -> new Count(rs.getString("name"), rs.getInt("n"))).list();
    }

    /** Skills most asked for in fresher-friendly postings found since {@code since}. */
    public List<Count> topFresherSkills(Instant since, Instant until, int limit) {
        return jdbc.sql("""
                        select skill as name, count(*) as n
                        from posting_tags t join postings p on p.id = t.posting_id, unnest(t.skills) as skill
                        where t.fresher_friendly and not p.baseline and p.first_seen_at >= :since and p.first_seen_at < :until
                        group by skill order by n desc, skill limit :limit
                        """)
                .param("since", Sql.ts(since)).param("until", Sql.ts(until)).param("limit", limit)
                .query((rs, row) -> new Count(rs.getString("name"), rs.getInt("n"))).list();
    }

    /** A row of /live: the posting and, once tagged, its tags. */
    public record LivePosting(UUID id, String company, String title, String location, String url,
            Instant firstSeenAt, String role, String seniority, String workMode, Double minYears, Double maxYears) {}

    /** The newest open postings, optionally of one role (database value, e.g. {@code backend}). */
    public List<LivePosting> latest(String role, int limit) {
        return jdbc.sql("""
                        select p.id, p.company, p.title, p.location_raw, p.url, p.first_seen_at,
                               t.role_category, t.seniority, t.work_mode, t.min_years, t.max_years
                        from postings p left join posting_tags t on t.posting_id = p.id
                        where not p.baseline and not p.is_repost and p.closed_at is null
                          and (cast(:role as text) is null or t.role_category = cast(:role as text))
                        order by p.first_seen_at desc
                        limit :limit
                        """)
                .param("role", role).param("limit", limit)
                .query((rs, row) -> new LivePosting(rs.getObject("id", UUID.class), rs.getString("company"),
                        rs.getString("title"), rs.getString("location_raw"), rs.getString("url"),
                        Sql.instant(rs, "first_seen_at"), rs.getString("role_category"), rs.getString("seniority"),
                        rs.getString("work_mode"), Sql.dbl(rs, "min_years"), Sql.dbl(rs, "max_years")))
                .list();
    }

    /** When the last tick that checked boards successfully (fully or partly) finished. */
    public Optional<Instant> lastSuccessfulTick() {
        return Optional.ofNullable(jdbc.sql("select max(finished_at) as t from ticks where status in ('ok', 'partial')")
                .query((rs, row) -> Sql.instant(rs, "t")).single());
    }

    public boolean databaseUp() {
        return jdbc.sql("select 1").query(Integer.class).single() == 1;
    }
}
