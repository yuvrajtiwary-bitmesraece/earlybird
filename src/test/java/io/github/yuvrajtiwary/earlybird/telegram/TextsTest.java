package io.github.yuvrajtiwary.earlybird.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.UserStats;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeFacts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextsTest {

    private static Profile profile(List<String> skills) {
        return new Profile(1, List.of(RoleCategory.BACKEND, RoleCategory.FULLSTACK), List.of("Bengaluru"), true, false,
                0.5, true, skills, List.of(), 2025, 55, 10, null, null, Instant.EPOCH);
    }

    @Test
    void userSuppliedTextIsEscaped() {
        assertThat(Texts.welcome("<script>")).contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(Texts.skills(List.of("C#", "R&D <tools>"))).contains("R&amp;D &lt;tools&gt;");
    }

    @Test
    void theSummaryShowsEveryAnswer() {
        String text = Texts.alertsOn(profile(List.of("Java", "Spring Boot")));

        assertThat(text).contains("Alerts are on", "Backend, Full-stack", "Bengaluru, Remote (India)",
                "Under 1 year", "included", "Java, Spring Boot");
    }

    @Test
    void noSkillsYetSuggestsUploadingAResume() {
        assertThat(Texts.alertsOn(profile(List.of()))).contains("/upload");
    }

    @Test
    void theConsentTextIsShownBeforeEveryUpload() {
        String consent = "sent to Google's Gemini API";
        assertThat(Texts.stepResume()).contains(consent, "email, phone and links removed", "isn't stored");
        assertThat(Texts.uploadPrompt()).contains(consent);
    }

    @Test
    void resumeResultMentionsAMismatchWithTheChosenExperience() {
        var facts = new ResumeFacts(List.of("Java"), 2.5, 2023, List.of());

        assertThat(Texts.resumeRead(facts, 0.0)).contains("about 2.5 years", "/prefs");
        assertThat(Texts.resumeRead(facts, 2.5)).doesNotContain("/prefs");
    }

    @Test
    void statsWithAndWithoutAlerts() {
        assertThat(Texts.stats(new UserStats(0, 0, 0, 0, 0, 0, null))).contains("No alerts yet");
        assertThat(Texts.stats(new UserStats(12, 9, 3, 11, 8, 2, "Kafka")))
                .contains("12 (9 instant, 3 in digests)", "8 of 10 rated", "Kafka");
    }

    @Test
    void experienceLabelsPickTheNearestOption() {
        assertThat(Labels.experience(0)).isEqualTo("Student / fresher");
        assertThat(Labels.experience(1.3)).isEqualTo("1–2 years");
        assertThat(Labels.experience(9)).isEqualTo("2–3 years");
    }
}
