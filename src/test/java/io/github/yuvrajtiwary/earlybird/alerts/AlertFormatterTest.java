package io.github.yuvrajtiwary.earlybird.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.Delivery;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.DigestItem;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardButton;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertFormatterTest {

    private static final Instant NOW = Instant.parse("2026-11-24T08:30:00Z");

    private static Delivery delivery(String title, List<String> cities, String mode, Double min, Double max,
            List<String> matched, List<String> missing) {
        Alert alert = new Alert(Ids.newId(), 1, Ids.newId(), 78, matched, missing, Alert.Mode.INSTANT,
                Alert.Status.QUEUED, NOW, null, null, null, null);
        return new Delivery(alert, 42, User.Status.ACTIVE, 10, title, "ExampleCo", "https://jobs.example.com/1",
                "Bengaluru, India", NOW.minus(Duration.ofMinutes(2)), null, cities, mode, min, max,
                List.of("Java", "Spring Boot", "PostgreSQL", "Kafka", "Docker"), "example.com");
    }

    @Test
    void matchesTheIssue10Layout() {
        Delivery d = delivery("Backend Engineer I", List.of("Bengaluru"), "hybrid", 0.0, 2.0,
                List.of("Java", "Spring Boot", "PostgreSQL"), List.of("Kafka", "Docker"));

        assertThat(new AlertFormatter("").alert(d, false, NOW)).isEqualTo("""
                🟢 <b>Strong match</b>
                ▰▰▰▰▰▰▰▰▱▱  78/100

                <b>Backend Engineer I</b>
                🏢 ExampleCo

                📍 Bengaluru · Hybrid
                🎓 0–2 yrs  ·  ⏱ found 2 min ago

                <blockquote>✅ <b>You have</b>
                Java · Spring Boot · PostgreSQL

                📚 <b>Worth learning</b>
                Kafka · Docker</blockquote>""");
    }

    @Test
    void recentMatchesAreLabelledAndTextIsEscaped() {
        Delivery d = delivery("<script>R&D</script>", List.of(), "remote", 3.0, null, List.of(), List.of());

        String text = new AlertFormatter("").alert(d, true, NOW);

        assertThat(text).startsWith("🟢 <b>Strong match</b> · <i>from the last 2 days</i>\n")
                .contains("<b>&lt;script&gt;R&amp;D&lt;/script&gt;</b>")
                .contains("📍 Bengaluru, India · Remote\n🎓 3+ yrs  ·  ⏱ found 2 min ago")
                .doesNotContain("<blockquote>"); // no skills to show
    }

    @Test
    void withoutYearsOrSkillsTheLinesCollapseCleanly() {
        Delivery d = new Delivery(delivery("T", List.of(), null, null, null, List.of(), List.of()).alert(), 42,
                User.Status.ACTIVE, 10, "T", "Co", "https://x", null, NOW, null, List.of(), null, null, null, List.of(), null);

        assertThat(new AlertFormatter("").alert(d, false, NOW)).endsWith("""
                📍 Location not stated
                ⏱ found just now

                <blockquote><i>The posting doesn't list specific skills.</i></blockquote>""");
    }

    @Test
    void tiersAndScoreBar() {
        assertThat(AlertFormatter.tier(85)).isEqualTo("Excellent match");
        assertThat(AlertFormatter.tierEmoji(85)).isEqualTo("🔥");
        assertThat(AlertFormatter.tier(84)).isEqualTo("Strong match");
        assertThat(AlertFormatter.tier(70)).isEqualTo("Strong match");
        assertThat(AlertFormatter.tier(69)).isEqualTo("Good match");
        assertThat(AlertFormatter.tierEmoji(40)).isEqualTo("🔵");
        assertThat(AlertFormatter.bar(0)).isEqualTo("▱▱▱▱▱▱▱▱▱▱");
        assertThat(AlertFormatter.bar(55)).isEqualTo("▰▰▰▰▰▰▱▱▱▱");
        assertThat(AlertFormatter.bar(100)).isEqualTo("▰▰▰▰▰▰▰▰▰▰");
    }

    @Test
    void theLogoComesFromTheCompanysDomainOrIsLeftOut() {
        Delivery withDomain = delivery("T", List.of(), null, null, null, List.of(), List.of());
        Delivery without = new Delivery(withDomain.alert(), 42, User.Status.ACTIVE, 10, "T", "Co", "https://x", null,
                NOW, null, List.of(), null, null, null, List.of(), null);

        assertThat(new AlertFormatter("").logoUrl(withDomain))
                .isEqualTo("https://www.google.com/s2/favicons?sz=128&domain=example.com");
        assertThat(new AlertFormatter("").logoUrl(without)).isNull();
    }

    @Test
    void buttonsAreApplyPrepAndFeedbackWithinTelegramsLimits() {
        var keyboard = new AlertFormatter("https://prep.example.com/dashboard?role={title}&company={company}&skills={skills}")
                .keyboard(delivery("Backend Engineer I", List.of(), "onsite", null, null, List.of(), List.of()));

        List<InlineKeyboardButton> links = keyboard.inlineKeyboard().get(0);
        assertThat(links).extracting(InlineKeyboardButton::text).containsExactly("Apply ↗", "Prep for this job ↗");
        assertThat(links.get(0).url()).isEqualTo("https://jobs.example.com/1");
        assertThat(links.get(1).url()).isEqualTo("https://prep.example.com/dashboard?role=Backend+Engineer+I"
                + "&company=ExampleCo&skills=Java%2CSpring+Boot%2CPostgreSQL%2CKafka%2CDocker");
        List<InlineKeyboardButton> feedback = keyboard.inlineKeyboard().get(1);
        assertThat(feedback).extracting(InlineKeyboardButton::text).containsExactly("👍 Relevant", "👎 Not for me");
        assertThat(feedback).allSatisfy(b -> assertThat(b.callbackData().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(64));
    }

    @Test
    void thePrepButtonIsHiddenWithoutATemplate() {
        var keyboard = new AlertFormatter("").keyboard(delivery("T", List.of(), null, null, null, List.of(), List.of()));
        assertThat(keyboard.inlineKeyboard().get(0)).extracting(InlineKeyboardButton::text).containsExactly("Apply ↗");
    }

    @Test
    void timeAgoAndYears() {
        assertThat(AlertFormatter.ago(NOW.minusSeconds(20), NOW)).isEqualTo("just now");
        assertThat(AlertFormatter.ago(NOW.minus(Duration.ofMinutes(5)), NOW)).isEqualTo("5 min ago");
        assertThat(AlertFormatter.ago(NOW.minus(Duration.ofHours(5)), NOW)).isEqualTo("5 h ago");
        assertThat(AlertFormatter.years(1.5, 3.0)).isEqualTo("1.5–3 yrs");
        assertThat(AlertFormatter.years(null, null)).isNull();
    }

    @Test
    void theDigestLinksEachJobAndEscapesUrls() {
        String text = new AlertFormatter("").digest(List.of(
                new DigestItem(Ids.newId(), 1, 42, "Backend Engineer", "Acme", "https://x.com/a?b=1&c=\"2\"", 62),
                new DigestItem(Ids.newId(), 1, 42, "Data Engineer", "Beta", "https://y.com", 48)));

        assertThat(text).startsWith("🌙 <b>Your evening digest</b>\n2 jobs from the last 24 hours, best first\n")
                .contains("<b>1.</b> <a href=\"https://x.com/a?b=1&amp;c=&quot;2&quot;\">Backend Engineer</a>\n🏢 Acme  ·  🔵 62/100")
                .contains("<b>2.</b> <a href=\"https://y.com\">Data Engineer</a>\n🏢 Beta  ·  🔵 48/100")
                .endsWith("⚙️ Change that with /prefs");
    }

    /** Telegram rejects a whole message over one unbalanced tag; board text full of markup must not cause one. */
    @Test
    void alertsAndDigestsAreWellFormedHtml() {
        var formatter = new AlertFormatter("");
        List<String> skills = List.of("C++", "<b>", "R&D");
        List<String> messages = List.of(
                formatter.alert(delivery("</b><i>Lead", List.of("<Pune>"), "remote", 1.0, 3.0, skills, skills), false, NOW),
                formatter.alert(delivery("T", List.of(), null, null, null, List.of(), List.of()), true, NOW),
                formatter.digest(List.of(new DigestItem(Ids.newId(), 1, 42, "<a>", "</a>", "https://x.com/?a=<b>", 99))));

        assertThat(messages).allSatisfy(m -> {
            assertThat(io.github.yuvrajtiwary.earlybird.support.HtmlBalance.unbalanced(m)).as(m).isEmpty();
            assertThat(m.length()).isLessThanOrEqualTo(4096);
        });
    }
}
