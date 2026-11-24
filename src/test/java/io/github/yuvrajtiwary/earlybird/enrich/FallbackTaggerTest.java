package io.github.yuvrajtiwary.earlybird.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.EmploymentType;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.RemoteScope;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Tagger;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FallbackTaggerTest {

    private final FallbackTagger tagger = new FallbackTagger(SkillVocabulary.fromClasspath());

    private PostingTags tag(String title, String location, String description) {
        Posting p = new Posting(Ids.newId(), 1, Source.GREENHOUSE, "1", "https://x", title, "Acme", location, List.of(),
                null, Instant.EPOCH, Instant.EPOCH, 0, null, "h", description, false, null, false);
        return tagger.tag(p, Instant.EPOCH);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Backend Engineer I | BACKEND",
        "Software Engineer, New Grad | BACKEND",
        "SDE-1 | BACKEND",
        "Full Stack Developer | FULLSTACK",
        "Frontend Engineer (React) | FRONTEND",
        "Android Developer | MOBILE",
        "Data Engineer | DATA_ENGINEERING",
        "Machine Learning Engineer | DATA_SCIENCE_ML",
        "Site Reliability Engineer | DEVOPS_SRE_CLOUD",
        "SDET II | QA_TEST",
        "Application Security Engineer | SECURITY",
        "Firmware Engineer | EMBEDDED",
        "Account Executive, Enterprise | OTHER",
    })
    void roleFromTitle(String title, RoleCategory role) {
        assertThat(FallbackTagger.role(title)).isEqualTo(role);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Software Engineering Intern | INTERN",
        "Graduate Software Engineer | NEW_GRAD",
        "Junior Backend Developer | JUNIOR",
        "SDE 1 | JUNIOR",
        "SDE-I | JUNIOR",
        "Software Engineer I | JUNIOR",
        "Software Engineer-I | JUNIOR",
        "Software Engineer II | UNKNOWN",
        "Senior Backend Engineer | SENIOR",
        "Staff Engineer | STAFF_PLUS",
        "Engineering Manager | STAFF_PLUS",
        "Backend Engineer | UNKNOWN",
    })
    void seniorityFromTitle(String title, Seniority seniority) {
        assertThat(FallbackTagger.seniority(title)).isEqualTo(seniority);
    }

    @Test
    void readsYearsWorkModeCitiesCountriesAndSkills() {
        PostingTags t = tag("Backend Engineer", "Bengaluru, India · Hybrid",
                "You have 2-4 years of experience building services in Java and Spring Boot on PostgreSQL. "
                        + "Bonus: Kafka, Docker. Strong communication skills.");

        assertThat(t.roleCategory()).isEqualTo(RoleCategory.BACKEND);
        assertThat(t.minYears()).isEqualTo(2.0);
        assertThat(t.maxYears()).isEqualTo(4.0);
        assertThat(t.fresherFriendly()).isFalse();
        assertThat(t.workMode()).isEqualTo(WorkMode.HYBRID);
        assertThat(t.cities()).containsExactly("Bengaluru");
        assertThat(t.countries()).contains("IN");
        assertThat(t.skills()).startsWith("Java", "Spring Boot", "PostgreSQL").contains("Kafka", "Docker");
        assertThat(t.skills()).doesNotContain("JavaScript"); // "Java" mustn't be read as JavaScript or vice versa
        assertThat(t.tagger()).isEqualTo(Tagger.FALLBACK);
    }

    @ParameterizedTest(name = "{0} -> {1}..{2}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        // Real Toast posting: was read as "5 years" (the 5 of 1.5), which hid it from freshers.
        "06 months - 1.5 years of experience as a software engineer | 0.5 | 1.5",
        "6 months to 2 years of experience | 0.5 | 2.0",
        "1.5+ years of experience | 1.5 | -",
        "2-4 years of experience | 2.0 | 4.0",
        "3 to 5 yrs building APIs | 3.0 | 5.0",
        "10+ years in distributed systems | 10.0 | -",
        "Founded in 2015. 3+ years with Go | 3.0 | -",
        "No experience requirement stated | - | -",
    })
    void yearsOfExperience(String text, Double min, Double max) {
        assertThat(FallbackTagger.years(text)).isEqualTo(new FallbackTagger.Years(min, max));
    }

    @Test
    void underAYearIsFresherFriendly() {
        PostingTags t = tag("Software Engineer-I", "Bengaluru,Karnataka,India",
                "Requirements: 06 months - 1.5 years of experience as a software engineer.");
        assertThat(t.seniority()).isEqualTo(Seniority.JUNIOR);
        assertThat(t.minYears()).isEqualTo(0.5);
        assertThat(t.fresherFriendly()).isTrue();
    }

    @Test
    void freshersAndInternships() {
        PostingTags intern = tag("Software Engineering Intern", "Pune", "Freshers welcome. Python, SQL.");
        assertThat(intern.seniority()).isEqualTo(Seniority.INTERN);
        assertThat(intern.employmentType()).isEqualTo(EmploymentType.INTERNSHIP);
        assertThat(intern.fresherFriendly()).isTrue();

        PostingTags zeroYears = tag("Backend Developer", "Chennai", "0-1 years of experience with Java.");
        assertThat(zeroYears.fresherFriendly()).isTrue();
        assertThat(zeroYears.minYears()).isZero();
    }

    @Test
    void remoteScopeFromTheText() {
        assertThat(tag("Engineer", "Remote", "Work from anywhere in the world.").remoteScope())
                .isEqualTo(RemoteScope.WORLDWIDE);
        assertThat(tag("Engineer", "Remote - India", "").remoteScope()).isEqualTo(RemoteScope.COUNTRY);
        assertThat(tag("Engineer", "Remote (EMEA)", "").remoteScope()).isEqualTo(RemoteScope.REGION);
        assertThat(tag("Engineer", "Bengaluru", "").remoteScope()).isEqualTo(RemoteScope.UNKNOWN);
        assertThat(tag("Engineer", "Remote - Worldwide", "").remoteScope()).isEqualTo(RemoteScope.WORLDWIDE);
        // Real case (Rubrik): company boilerplate isn't a statement about where you can work from.
        assertThat(tag("Account Executive", "Remote - New Jersey",
                "Rubrik is a global leader in cyber resilience, serving customers globally.").remoteScope())
                .isEqualTo(RemoteScope.UNKNOWN);
    }

    @Test
    void leversCountryCodeIsRead() {
        assertThat(tag("Backend Engineer", "tamil nadu, IN · On-site", "").countries()).containsExactly("IN");
    }
}
