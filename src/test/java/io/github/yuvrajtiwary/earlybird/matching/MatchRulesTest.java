package io.github.yuvrajtiwary.earlybird.matching;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.matching.MatchRules.Match;
import io.github.yuvrajtiwary.earlybird.matching.MatchRules.Route;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.EmploymentType;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.RemoteScope;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Tagger;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The spec's matching rules (section 3.9), one table per rule. */
class MatchRulesTest {

    private static PostingTags job(Seniority seniority, Double min, boolean fresher, EmploymentType employment,
            WorkMode mode, RemoteScope scope, List<String> cities, List<String> countries, List<String> skills) {
        return new PostingTags(UUID.randomUUID(), RoleCategory.BACKEND, seniority, min, null, fresher, employment, mode,
                scope, cities, countries, skills, null, Tagger.GEMINI, "m", Instant.EPOCH);
    }

    private static PostingTags onsite(String city) {
        return job(Seniority.JUNIOR, 0.0, true, EmploymentType.FULL_TIME, WorkMode.ONSITE, RemoteScope.UNKNOWN,
                List.of(city), List.of("IN"), List.of("Java"));
    }

    private static Profile user(List<String> cities, boolean remoteIndia, boolean remoteWorldwide, double years,
            boolean internships, List<String> skills) {
        return new Profile(1, List.of(RoleCategory.BACKEND), cities, remoteIndia, remoteWorldwide, years, internships,
                skills, List.of(), null, 55, 10, null, null, Instant.EPOCH);
    }

    private static Profile inBengaluru() {
        return user(List.of("Bengaluru"), false, false, 0, false, List.of("Java"));
    }

    // ------------------------------------------------------------------------------------------ score

    @Test
    void theSpecsScoreFormula() {
        // 3 of 4 job skills, fresher-friendly (exp 1.0), exact city (loc 1.0):
        // round(100 * (0.65 * 0.75 + 0.20 * 1.0 + 0.15 * 1.0)) = round(83.75) = 84
        PostingTags j = job(Seniority.JUNIOR, 0.0, true, EmploymentType.FULL_TIME, WorkMode.ONSITE, RemoteScope.UNKNOWN,
                List.of("Bengaluru"), List.of("IN"), List.of("Java", "Spring Boot", "PostgreSQL", "Kafka"));
        Profile u = user(List.of("Bengaluru"), false, false, 0, false, List.of("java", "Spring Boot", "PostgreSQL", "Go"));

        Match m = MatchRules.evaluate(j, u).orElseThrow();

        assertThat(m.score()).isEqualTo(84);
        assertThat(m.matchedSkills()).containsExactly("Java", "Spring Boot", "PostgreSQL"); // job's order, case-insensitive
        assertThat(m.missingSkills()).containsExactly("Kafka");
    }

    @Test
    void aJobListingNoSkillsCountsAsHalfASkillMatch() {
        PostingTags j = job(Seniority.UNKNOWN, null, false, EmploymentType.FULL_TIME, WorkMode.HYBRID, RemoteScope.UNKNOWN,
                List.of("Bengaluru"), List.of(), List.of());
        // 0.65*0.5 + 0.20*0.7 (unknown years) + 0.15*0.8 (hybrid) = 0.325 + 0.14 + 0.12 = 0.585
        assertThat(MatchRules.evaluate(j, inBengaluru()).orElseThrow().score()).isEqualTo(59);
    }

    @Test
    void showsAtMostSixMatchedAndFourMissingSkills() {
        List<String> skills = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "B1", "B2", "B3", "B4", "B5");
        PostingTags j = job(Seniority.JUNIOR, 0.0, true, EmploymentType.FULL_TIME, WorkMode.ONSITE, RemoteScope.UNKNOWN,
                List.of("Bengaluru"), List.of(), skills);
        Profile u = user(List.of("Bengaluru"), false, false, 0, false, List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7"));

        Match m = MatchRules.evaluate(j, u).orElseThrow();

        assertThat(m.matchedSkills()).hasSize(6);
        assertThat(m.missingSkills()).containsExactly("B1", "B2", "B3", "B4");
    }

    // ------------------------------------------------------------------------------------------ location

    @ParameterizedTest(name = "{0} job in {1}, user in {2} -> {3}")
    @CsvSource(nullValues = "null", value = {
        "ONSITE, Bengaluru, Bengaluru, 1.0",
        "ONSITE, Bangalore, Bengaluru, 1.0",      // same city, other spelling
        "HYBRID, Bengaluru, Bengaluru, 0.8",      // hybrid is a weaker match
        "ONSITE, Delhi, Delhi NCR, 1.0",
        "ONSITE, Gurgaon, Delhi NCR, 0.8",        // city group
        "ONSITE, Noida, Delhi NCR, 0.8",
        "ONSITE, Thane, Mumbai, 0.8",
        "ONSITE, Pune, Bengaluru, null",          // wrong city
        "UNKNOWN, Pune, Pune, 1.0",               // unstated work mode: judged by city
        "ONSITE, London, Bengaluru, null",
    })
    void cityRules(WorkMode mode, String jobCity, String userCity, Double fit) {
        PostingTags j = job(Seniority.JUNIOR, 0.0, true, EmploymentType.FULL_TIME, mode, RemoteScope.UNKNOWN,
                List.of(jobCity), List.of(), List.of());
        assertThat(MatchRules.locationFit(j, user(List.of(userCity), false, false, 0, false, List.of()))).isEqualTo(fit);
    }

    @ParameterizedTest(name = "remote {0} {1}: India user {2}, worldwide user {3}")
    @CsvSource(nullValues = "null", value = {
        "WORLDWIDE, '',    1.0, 1.0",
        "UNKNOWN,   '',    1.0, null",
        "COUNTRY,   IN,    1.0, null",
        "COUNTRY,   US,    null, null",
        "REGION,    IN;SG, 1.0, null",            // e.g. APAC
        "REGION,    GB;DE, null, null",           // e.g. EMEA
        "REGION,    '',    1.0, null",
    })
    void remoteRules(RemoteScope scope, String countries, Double indiaFit, Double worldwideFit) {
        List<String> codes = countries.isBlank() ? List.of() : List.of(countries.split(";"));
        PostingTags j = job(Seniority.JUNIOR, 0.0, true, EmploymentType.FULL_TIME, WorkMode.REMOTE, scope, List.of(),
                codes, List.of());
        assertThat(MatchRules.locationFit(j, user(List.of(), true, false, 0, false, List.of()))).isEqualTo(indiaFit);
        assertThat(MatchRules.locationFit(j, user(List.of(), false, true, 0, false, List.of()))).isEqualTo(worldwideFit);
        assertThat(MatchRules.locationFit(j, user(List.of("Bengaluru"), false, false, 0, false, List.of())))
                .as("city-only users don't get remote jobs").isNull();
    }

    // ------------------------------------------------------------------------------------------ experience

    @ParameterizedTest(name = "{0}, min {1}, fresher {2}, user {3} yrs -> {4}")
    @CsvSource(nullValues = "null", value = {
        "JUNIOR,    0,    true,  0,   1.0",
        "UNKNOWN,   null, false, 0,   0.7",     // nothing stated: allowed, partial fit
        "NEW_GRAD,  null, false, 0,   0.7",
        "MID,       null, false, 0,   null",    // mid-level with no years: not for a fresher
        "UNKNOWN,   1,    false, 0,   0.7",     // one year above the user
        "UNKNOWN,   1,    false, 1.5, 1.0",
        "UNKNOWN,   2,    false, 0,   null",    // two years above: fail
        "UNKNOWN,   2,    false, 0.5, null",    // 2 >= 0.5 + 2? no; 2 <= 1.5? no; stated min not entry -> fail
        "SENIOR,    0,    true,  0,   null",    // senior always fails
        "STAFF_PLUS, null, false, 2.5, null",
        "MID,       3,    false, 2.5, 0.7",
    })
    void experienceRules(Seniority seniority, Double min, boolean fresher, double userYears, Double fit) {
        PostingTags j = job(seniority, min, fresher, EmploymentType.FULL_TIME, WorkMode.ONSITE, RemoteScope.UNKNOWN,
                List.of("Bengaluru"), List.of(), List.of());
        assertThat(MatchRules.experienceFit(j, userYears)).isEqualTo(fit);
    }

    // ------------------------------------------------------------------------------------------ employment, role, routing

    @Test
    void internshipsOnlyForUsersWhoWantThem() {
        PostingTags intern = job(Seniority.INTERN, null, true, EmploymentType.INTERNSHIP, WorkMode.ONSITE,
                RemoteScope.UNKNOWN, List.of("Bengaluru"), List.of(), List.of("Java"));

        assertThat(MatchRules.evaluate(intern, user(List.of("Bengaluru"), false, false, 0, false, List.of()))).isEmpty();
        assertThat(MatchRules.evaluate(intern, user(List.of("Bengaluru"), false, false, 0, true, List.of()))).isPresent();
    }

    @Test
    void contractAndPartTimeNeverMatch() {
        for (EmploymentType type : List.of(EmploymentType.CONTRACT, EmploymentType.PART_TIME)) {
            PostingTags j = job(Seniority.JUNIOR, 0.0, true, type, WorkMode.ONSITE, RemoteScope.UNKNOWN,
                    List.of("Bengaluru"), List.of(), List.of());
            assertThat(MatchRules.evaluate(j, inBengaluru())).isEmpty();
        }
    }

    @Test
    void aRoleTheUserDidntPickNeverMatches() {
        PostingTags frontend = new PostingTags(UUID.randomUUID(), RoleCategory.FRONTEND, Seniority.JUNIOR, 0.0, null,
                true, EmploymentType.FULL_TIME, WorkMode.ONSITE, RemoteScope.UNKNOWN, List.of("Bengaluru"), List.of(),
                List.of(), null, Tagger.GEMINI, "m", Instant.EPOCH);
        assertThat(MatchRules.evaluate(frontend, inBengaluru())).isEmpty();
    }

    @ParameterizedTest(name = "score {0}, threshold {1}, baseline {2} -> {3}")
    @CsvSource({
        "84, 55, false, INSTANT",
        "55, 55, false, INSTANT",
        "54, 55, false, DIGEST",
        "40, 55, false, DIGEST",
        "39, 55, false, NONE",
        "95, 55, true,  DIGEST",   // a job that was already up when we started watching is never "new"
        "39, 55, true,  NONE",
    })
    void routing(int score, int minScore, boolean baseline, Route route) {
        assertThat(MatchRules.route(score, minScore, baseline)).isEqualTo(route);
    }
}
