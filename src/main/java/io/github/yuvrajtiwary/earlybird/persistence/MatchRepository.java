package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads for the matcher. */
@Repository
public class MatchRepository {

    public record Candidate(long userId, long chatId, Profile profile) {}

    public record TaggedPosting(Posting posting, PostingTags tags) {}

    private final JdbcClient jdbc;

    public MatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Active users who want this role category and haven't had an alert for this posting. The GIN index on
     * {@code profiles.roles} answers "roles contains X"; location, experience and scoring happen in Java, where
     * the rules are readable and unit-tested.
     */
    public List<Candidate> candidates(RoleCategory category, UUID postingId) {
        return jdbc.sql("""
                        select u.id as uid, u.chat_id, pr.*
                        from users u
                        join profiles pr on pr.user_id = u.id
                        where u.status = 'active'
                          and pr.roles @> array[cast(:category as text)]
                          and not exists (select 1 from alerts a where a.user_id = u.id and a.posting_id = :posting)
                        """)
                .param("category", Sql.db(category))
                .param("posting", postingId)
                .query((rs, row) -> new Candidate(rs.getLong("uid"), rs.getLong("chat_id"), ProfileRepository.map(rs, row)))
                .list();
    }

    public Optional<TaggedPosting> findTagged(UUID postingId) {
        return jdbc.sql("select * from postings p join posting_tags t on t.posting_id = p.id where p.id = :id")
                .param("id", postingId)
                .query((rs, row) -> new TaggedPosting(PostingRepository.map(rs, row), PostingTagsRepository.map(rs, row)))
                .optional();
    }

    /** Open, tagged, non-repost postings first seen since {@code since}, in these categories, not yet sent to the user. */
    public List<TaggedPosting> recentFor(long userId, Collection<RoleCategory> roles, Instant since) {
        if (roles.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select * from postings p join posting_tags t on t.posting_id = p.id
                        where p.first_seen_at >= :since and p.closed_at is null and not p.is_repost
                          and t.role_category = any(:roles)
                          and not exists (select 1 from alerts a where a.user_id = :u and a.posting_id = p.id)
                        """)
                .param("since", Sql.ts(since))
                .param("roles", Sql.enumArray(roles))
                .param("u", userId)
                .query((rs, row) -> new TaggedPosting(PostingRepository.map(rs, row), PostingTagsRepository.map(rs, row)))
                .list();
    }
}
