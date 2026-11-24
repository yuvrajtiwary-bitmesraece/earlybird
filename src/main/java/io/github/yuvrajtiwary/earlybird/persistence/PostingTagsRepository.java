package io.github.yuvrajtiwary.earlybird.persistence;

import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.EmploymentType;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.RemoteScope;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Tagger;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PostingTagsRepository {

    private final JdbcClient jdbc;

    public PostingTagsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts or replaces a posting's tags (re-tagging, e.g. Gemini after the fallback, overwrites). */
    public void upsert(PostingTags t) {
        jdbc.sql("""
                        insert into posting_tags (posting_id, role_category, seniority, min_years, max_years,
                            fresher_friendly, employment_type, work_mode, remote_scope, cities, countries, skills,
                            summary, tagger, model, enriched_at)
                        values (:postingId, :roleCategory, :seniority, :minYears, :maxYears,
                            :fresherFriendly, :employmentType, :workMode, :remoteScope, :cities, :countries, :skills,
                            :summary, :tagger, :model, :enrichedAt)
                        on conflict (posting_id) do update set
                            role_category = excluded.role_category,
                            seniority = excluded.seniority,
                            min_years = excluded.min_years,
                            max_years = excluded.max_years,
                            fresher_friendly = excluded.fresher_friendly,
                            employment_type = excluded.employment_type,
                            work_mode = excluded.work_mode,
                            remote_scope = excluded.remote_scope,
                            cities = excluded.cities,
                            countries = excluded.countries,
                            skills = excluded.skills,
                            summary = excluded.summary,
                            tagger = excluded.tagger,
                            model = excluded.model,
                            enriched_at = excluded.enriched_at
                        """)
                .param("postingId", t.postingId())
                .param("roleCategory", Sql.db(t.roleCategory()))
                .param("seniority", Sql.db(t.seniority()))
                .param("minYears", t.minYears())
                .param("maxYears", t.maxYears())
                .param("fresherFriendly", t.fresherFriendly())
                .param("employmentType", Sql.db(t.employmentType()))
                .param("workMode", Sql.db(t.workMode()))
                .param("remoteScope", Sql.db(t.remoteScope()))
                .param("cities", Sql.textArray(t.cities()))
                .param("countries", Sql.textArray(t.countries()))
                .param("skills", Sql.textArray(t.skills()))
                .param("summary", t.summary())
                .param("tagger", Sql.db(t.tagger()))
                .param("model", t.model())
                .param("enrichedAt", Sql.ts(t.enrichedAt()))
                .update();
    }

    public Optional<PostingTags> findByPostingId(UUID postingId) {
        return jdbc.sql("select * from posting_tags where posting_id = :id")
                .param("id", postingId)
                .query(PostingTagsRepository::map)
                .optional();
    }

    static PostingTags map(ResultSet rs, int row) throws SQLException {
        return new PostingTags(
                rs.getObject("posting_id", UUID.class),
                Sql.enumOf(RoleCategory.class, rs.getString("role_category")),
                Sql.enumOf(Seniority.class, rs.getString("seniority")),
                Sql.dbl(rs, "min_years"),
                Sql.dbl(rs, "max_years"),
                rs.getBoolean("fresher_friendly"),
                Sql.enumOf(EmploymentType.class, rs.getString("employment_type")),
                Sql.enumOf(WorkMode.class, rs.getString("work_mode")),
                Sql.enumOf(RemoteScope.class, rs.getString("remote_scope")),
                Sql.list(rs, "cities"),
                Sql.list(rs, "countries"),
                Sql.list(rs, "skills"),
                rs.getString("summary"),
                Sql.enumOf(Tagger.class, rs.getString("tagger")),
                rs.getString("model"),
                Sql.instant(rs, "enriched_at"));
    }
}
