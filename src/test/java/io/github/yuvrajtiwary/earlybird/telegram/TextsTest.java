package io.github.yuvrajtiwary.earlybird.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.UserStats;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeFacts;
import io.github.yuvrajtiwary.earlybird.support.HtmlBalance;
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
                .contains("12 alerts: 9 instant, 3 in digests", "8 of 10 rated relevant", "Most-missed skill: Kafka");
    }

    @Test
    void experienceLabelsPickTheNearestOption() {
        assertThat(Labels.experience(0)).isEqualTo("Student / fresher");
        assertThat(Labels.experience(1.3)).isEqualTo("1–2 years");
        assertThat(Labels.experience(9)).isEqualTo("2–3 years");
    }

    @Test
    void onboardingStepsShowTheirProgress() {
        assertThat(Texts.stepRoles())
                .isEqualTo("<b>Roles</b> · step 1 of 5\nWhich kinds of roles do you want? Pick all that fit, then tap Done.");
        assertThat(Texts.stepInternships()).startsWith("<b>Internships</b> · step 4 of 5");
        assertThat(Texts.stepResume()).startsWith("<b>Resume</b> · step 5 of 5");
    }

    @Test
    void longSkillListsAreShortenedOnTheProfileCardOnly() {
        List<String> many = java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> "Skill" + i).toList();

        assertThat(Texts.alertsOn(profile(many))).contains("Skill10 +10 more").doesNotContain("Skill11");
        assertThat(Texts.skills(many)).contains("Skill20").doesNotContain("more");
        assertThat(Texts.skillList(List.of("A", "B"), 1)).isEqualTo("A +1 more");
    }

    /** Telegram rejects a whole message over one unbalanced tag, so every text is checked, filled in fully. */
    @Test
    void everyMessageIsWellFormedHtmlWithinTelegramsLimit() {
        var facts = new ResumeFacts(List.of("Java", "R&D"), 2.5, 2023, List.of());
        List<String> all = new java.util.ArrayList<>(List.of(Texts.welcome("<b>Asha"), Texts.stepRoles(),
                Texts.stepLocations(), Texts.stepExperience(), Texts.stepInternships(), Texts.stepResume(),
                Texts.alertsOn(profile(List.of("C++"))), Texts.alertsOn(profile(List.of())), Texts.prefs(profile(List.of("Go"))),
                Texts.editRoles(), Texts.editLocations(), Texts.editExperience(), Texts.editInternships(),
                Texts.minScorePrompt(), Texts.dailyCapPrompt(), Texts.uploadPrompt(), Texts.resumeRead(facts, 0),
                Texts.resumeRead(new ResumeFacts(List.of(), null, null, List.of()), 0), Texts.sameResume(profile(List.of("Go"))),
                Texts.sameResume(profile(List.of())), Texts.uploadCancelled(), Texts.skills(List.of()), Texts.skills(List.of("<x>")),
                Texts.stats(new UserStats(0, 0, 0, 0, 0, 0, null)), Texts.stats(new UserStats(1, 1, 0, 1, 1, 0, "K<8s>")),
                Texts.help(), Texts.confirmDelete(), Texts.deleted(), Texts.keptEverything(), Texts.needStart(),
                Texts.finishSetupFirst(), Texts.paused(), Texts.unpaused(), Texts.unknownCommand(), Texts.notACommand(),
                Texts.sendPdfFirst(), Texts.slowDown()));
        for (var reason : io.github.yuvrajtiwary.earlybird.profiles.ResumeAnalyzer.ResumeException.Reason.values()) {
            all.add(Texts.resumeProblem(reason));
        }

        assertThat(all).allSatisfy(text -> {
            assertThat(text.length()).isLessThanOrEqualTo(4096);
            assertThat(HtmlBalance.unbalanced(text)).as(text).isEmpty();
        });
    }
}
