package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PostingRepository {

    private static final String INSERT = """
            insert into postings (id, board_id, source, external_id, url, title, company, location_raw,
                departments, source_published_at, first_seen_at, last_seen_at, missing_count, closed_at,
                content_hash, description_text, is_repost, event_published_at, baseline)
            values (:id, :boardId, :source, :externalId, :url, :title, :company, :locationRaw,
                :departments, :sourcePublishedAt, :firstSeenAt, :lastSeenAt, :missingCount, :closedAt,
                :contentHash, :descriptionText, :repost, :eventPublishedAt, :baseline)
            on conflict (board_id, external_id) do nothing
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public PostingRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * Inserts a newly seen posting. Returns false if this board already has a posting with the same
     * external id: the unique constraint, not a prior lookup, decides, so two concurrent ticks can't both
     * insert it.
     */
    public boolean insertIfAbsent(Posting p) {
        return jdbc.sql(INSERT).paramSource(params(p)).update() == 1;
    }

    /**
     * Batch version of {@link #insertIfAbsent}: one round trip for a whole board's new jobs.
     *
     * @return the postings that were actually inserted (the rest already existed)
     */
    public List<Posting> insertAll(List<Posting> postings) {
        if (postings.isEmpty()) {
            return List.of();
        }
        int[] counts = named.batchUpdate(INSERT,
                postings.stream().map(PostingRepository::params).toArray(SqlParameterSource[]::new));
        List<Posting> inserted = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 1) {
                inserted.add(postings.get(i));
            }
        }
        return inserted;
    }

    public Optional<Posting> findById(UUID id) {
        return jdbc.sql("select * from postings where id = :id")
                .param("id", id)
                .query(PostingRepository::map)
                .optional();
    }

    public List<Posting> findByBoard(long boardId) {
        return jdbc.sql("select * from postings where board_id = :b order by first_seen_at, external_id")
                .param("b", boardId)
                .query(PostingRepository::map)
                .list();
    }

    /** Which of these external ids this board already has (open or closed). */
    public Set<String> findExternalIds(long boardId, Collection<String> externalIds) {
        if (externalIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select external_id from postings where board_id = :b and external_id = any(:ids)")
                .param("b", boardId)
                .param("ids", Sql.textArray(externalIds))
                .query(String.class)
                .list());
    }

    /**
     * Jobs still listed: refresh last_seen_at and reset the miss counter. A posting that had been closed and
     * shows up again is reopened (boards occasionally drop a job for a few minutes); its alerts aren't resent.
     */
    public int markSeen(long boardId, Collection<String> externalIds, Instant now) {
        if (externalIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        update postings set last_seen_at = :now, missing_count = 0, closed_at = null
                        where board_id = :b and external_id = any(:ids)
                        """)
                .param("now", Sql.ts(now))
                .param("b", boardId)
                .param("ids", Sql.textArray(externalIds))
                .update();
    }

    /**
     * Open postings that are no longer listed get one more miss; at {@code closeAfterMisses} they close.
     * One statement does both, so it can't half-apply.
     *
     * @return ids of the postings closed by this call
     */
    public List<UUID> markMissing(long boardId, Collection<String> currentExternalIds, Instant now,
            int closeAfterMisses) {
        return jdbc.sql("""
                        with missed as (
                            update postings set missing_count = missing_count + 1,
                                closed_at = case when missing_count + 1 >= :closeAfter then :now end
                            where board_id = :b and closed_at is null and not (external_id = any(:ids))
                            returning id, closed_at
                        )
                        select id from missed where closed_at is not null
                        """)
                .param("closeAfter", closeAfterMisses)
                .param("now", Sql.ts(now))
                .param("b", boardId)
                .param("ids", Sql.textArray(currentExternalIds))
                .query(UUID.class)
                .list();
    }

    /** Content hashes from this set seen on any posting since {@code since} (repost detection). */
    public Set<String> findHashesSeenSince(Collection<String> hashes, Instant since) {
        if (hashes.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        select distinct content_hash from postings
                        where content_hash = any(:hashes) and first_seen_at >= :since
                        """)
                .param("hashes", Sql.textArray(hashes))
                .param("since", Sql.ts(since))
                .query(String.class)
                .list());
    }

    /** Outbox-lite: called only after the event bus accepted the PostingDiscovered events. */
    public int markPublished(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("update postings set event_published_at = :now where id = any(cast(:ids as uuid[]))")
                .param("now", Sql.ts(now))
                .param("ids", ids.stream().map(UUID::toString).toArray(String[]::new))
                .update();
    }

    private static SqlParameterSource params(Posting p) {
        return new MapSqlParameterSource()
                .addValue("id", p.id())
                .addValue("boardId", p.boardId())
                .addValue("source", Sql.db(p.source()))
                .addValue("externalId", p.externalId())
                .addValue("url", p.url())
                .addValue("title", p.title())
                .addValue("company", p.company())
                .addValue("locationRaw", p.locationRaw())
                .addValue("departments", Sql.textArray(p.departments()))
                .addValue("sourcePublishedAt", Sql.ts(p.sourcePublishedAt()))
                .addValue("firstSeenAt", Sql.ts(p.firstSeenAt()))
                .addValue("lastSeenAt", Sql.ts(p.lastSeenAt()))
                .addValue("missingCount", p.missingCount())
                .addValue("closedAt", Sql.ts(p.closedAt()))
                .addValue("contentHash", p.contentHash())
                .addValue("descriptionText", p.descriptionText())
                .addValue("repost", p.repost())
                .addValue("eventPublishedAt", Sql.ts(p.eventPublishedAt()))
                .addValue("baseline", p.baseline());
    }

    static Posting map(ResultSet rs, int row) throws SQLException {
        return new Posting(
                rs.getObject("id", UUID.class),
                rs.getLong("board_id"),
                Sql.enumOf(Source.class, rs.getString("source")),
                rs.getString("external_id"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("company"),
                rs.getString("location_raw"),
                Sql.list(rs, "departments"),
                Sql.instant(rs, "source_published_at"),
                Sql.instant(rs, "first_seen_at"),
                Sql.instant(rs, "last_seen_at"),
                rs.getInt("missing_count"),
                Sql.instant(rs, "closed_at"),
                rs.getString("content_hash"),
                rs.getString("description_text"),
                rs.getBoolean("is_repost"),
                Sql.instant(rs, "event_published_at"),
                rs.getBoolean("baseline"));
    }
}
