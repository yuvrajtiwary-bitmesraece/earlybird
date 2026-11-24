package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PostingRepository {

    private final JdbcClient jdbc;

    public PostingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a newly seen posting. Returns false if this board already has a posting with the same
     * external id: the unique constraint, not a prior lookup, decides, so two concurrent ticks can't both
     * insert it.
     */
    public boolean insertIfAbsent(Posting p) {
        return jdbc.sql("""
                        insert into postings (id, board_id, source, external_id, url, title, company, location_raw,
                            departments, source_published_at, first_seen_at, last_seen_at, missing_count, closed_at,
                            content_hash, description_text, is_repost, event_published_at)
                        values (:id, :boardId, :source, :externalId, :url, :title, :company, :locationRaw,
                            :departments, :sourcePublishedAt, :firstSeenAt, :lastSeenAt, :missingCount, :closedAt,
                            :contentHash, :descriptionText, :repost, :eventPublishedAt)
                        on conflict (board_id, external_id) do nothing
                        """)
                .param("id", p.id())
                .param("boardId", p.boardId())
                .param("source", Sql.db(p.source()))
                .param("externalId", p.externalId())
                .param("url", p.url())
                .param("title", p.title())
                .param("company", p.company())
                .param("locationRaw", p.locationRaw())
                .param("departments", Sql.textArray(p.departments()))
                .param("sourcePublishedAt", Sql.ts(p.sourcePublishedAt()))
                .param("firstSeenAt", Sql.ts(p.firstSeenAt()))
                .param("lastSeenAt", Sql.ts(p.lastSeenAt()))
                .param("missingCount", p.missingCount())
                .param("closedAt", Sql.ts(p.closedAt()))
                .param("contentHash", p.contentHash())
                .param("descriptionText", p.descriptionText())
                .param("repost", p.repost())
                .param("eventPublishedAt", Sql.ts(p.eventPublishedAt()))
                .update() == 1;
    }

    public Optional<Posting> findById(UUID id) {
        return jdbc.sql("select * from postings where id = :id")
                .param("id", id)
                .query(PostingRepository::map)
                .optional();
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
                Sql.instant(rs, "event_published_at"));
    }
}
