package io.github.yuvrajtiwary.earlybird.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {

    private final JdbcClient jdbc;

    public ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(Profile p) {
        jdbc.sql("""
                        insert into profiles (user_id, roles, cities, remote_india, remote_worldwide, years_experience,
                            include_internships, skills, suggested_roles, graduation_year, min_score, daily_cap,
                            resume_sha256, resume_uploaded_at, updated_at)
                        values (:userId, :roles, :cities, :remoteIndia, :remoteWorldwide, :yearsExperience,
                            :includeInternships, :skills, :suggestedRoles, :graduationYear, :minScore, :dailyCap,
                            :resumeSha256, :resumeUploadedAt, :updatedAt)
                        on conflict (user_id) do update set
                            roles = excluded.roles,
                            cities = excluded.cities,
                            remote_india = excluded.remote_india,
                            remote_worldwide = excluded.remote_worldwide,
                            years_experience = excluded.years_experience,
                            include_internships = excluded.include_internships,
                            skills = excluded.skills,
                            suggested_roles = excluded.suggested_roles,
                            graduation_year = excluded.graduation_year,
                            min_score = excluded.min_score,
                            daily_cap = excluded.daily_cap,
                            resume_sha256 = excluded.resume_sha256,
                            resume_uploaded_at = excluded.resume_uploaded_at,
                            updated_at = excluded.updated_at
                        """)
                .param("userId", p.userId())
                .param("roles", Sql.enumArray(p.roles()))
                .param("cities", Sql.textArray(p.cities()))
                .param("remoteIndia", p.remoteIndia())
                .param("remoteWorldwide", p.remoteWorldwide())
                .param("yearsExperience", p.yearsExperience())
                .param("includeInternships", p.includeInternships())
                .param("skills", Sql.textArray(p.skills()))
                .param("suggestedRoles", Sql.enumArray(p.suggestedRoles()))
                .param("graduationYear", p.graduationYear())
                .param("minScore", p.minScore())
                .param("dailyCap", p.dailyCap())
                .param("resumeSha256", p.resumeSha256())
                .param("resumeUploadedAt", Sql.ts(p.resumeUploadedAt()))
                .param("updatedAt", Sql.ts(p.updatedAt()))
                .update();
    }

    public Optional<Profile> findByUserId(long userId) {
        return jdbc.sql("select * from profiles where user_id = :id")
                .param("id", userId)
                .query(ProfileRepository::map)
                .optional();
    }

    static Profile map(ResultSet rs, int row) throws SQLException {
        return new Profile(
                rs.getLong("user_id"),
                Sql.enumList(rs, "roles", RoleCategory.class),
                Sql.list(rs, "cities"),
                rs.getBoolean("remote_india"),
                rs.getBoolean("remote_worldwide"),
                Sql.dbl(rs, "years_experience"),
                rs.getBoolean("include_internships"),
                Sql.list(rs, "skills"),
                Sql.enumList(rs, "suggested_roles", RoleCategory.class),
                Sql.intOrNull(rs, "graduation_year"),
                rs.getInt("min_score"),
                rs.getInt("daily_cap"),
                rs.getString("resume_sha256"),
                Sql.instant(rs, "resume_uploaded_at"),
                Sql.instant(rs, "updated_at"));
    }
}
