package io.github.yuvrajtiwary.earlybird.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.Delivery;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.DigestItem;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.support.HtmlBalance;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardButton;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

    private static Delivery bare(String domain) {
        Delivery d = delivery("T", List.of(), null, null, null, List.of(), List.of());
        return new Delivery(d.alert(), 42, User.Status.ACTIVE, 10, "T", "Co", "https://x", null, NOW, null, List.of(),
                null, null, null, List.of(), domain);
    }

    @Test
    void matchesTheMinimalCardLayout() {
        Delivery d = delivery("Backend Engineer I", List.of("Bengaluru"), "hybrid", 0.0, 2.0,
                List.of("Java", "Spring Boot", "PostgreSQL"), List.of("Kafka", "Docker"));

        assertThat(new AlertFormatter("").alert(d, false, NOW)).isEqualTo("""
                <b>Backend Engineer I</b>
                ExampleCo · Bengaluru · Hybrid

                78% match · 0–2 yrs · 2 min ago
                You have: Java, Spring Boot, PostgreSQL
                To learn: Kafka, Docker""");
    }

    @Test
    void recentMatchesAreLabelledAndTextIsEscaped() {
        Delivery d = delivery("<script>R&D</script>", List.of(), "remote", 3.0, null, List.of(), List.of());

        String text = new AlertFormatter("").alert(d, true, NOW);

        assertThat(text).startsWith("<b>&lt;script&gt;R&amp;D&lt;/script&gt;</b>\nExampleCo · Bengaluru, India · Remote\n")
                .contains("78% match · 3+ yrs · 2 min ago")
                .doesNotContain("You have", "To learn")
                .endsWith("\n\n<i>A recent match from the last 2 days</i>");
    }

    @Test
    void withoutYearsOrSkillsTheLinesCollapseCleanly() {
        assertThat(new AlertFormatter("").alert(bare(null), false, NOW)).isEqualTo("""
                <b>T</b>
                Co · Location not stated

                78% match · just now
                The posting doesn't list specific skills.""");
    }

    @Test
    void theLogoCardIsUsedOnlyWhenTheCompanyHasOne() {
        var formatter = new AlertFormatter("", "https://eb.example/logos/", Set.of("example.com"));

        assertThat(formatter.logoUrl(bare("example.com"))).isEqualTo("https://eb.example/logos/example.com.png?v=1");
        assertThat(formatter.logoUrl(bare("other.com"))).isNull(); // no card: plain alert, never a blurry favicon
        assertThat(formatter.logoUrl(bare(null))).isNull();
        assertThat(new AlertFormatter("").logoUrl(bare("example.com"))).isNull();
    }

    @Test
    void withoutAPrepLinkTheButtonsFitOneRow() {
        var keyboard = new AlertFormatter("").keyboard(bare(null)).inlineKeyboard();

        assertThat(keyboard).singleElement().satisfies(row -> {
            assertThat(row).extracting(InlineKeyboardButton::text).containsExactly("Apply ↗", "👍", "👎");
            assertThat(row.get(0).url()).isEqualTo("https://x");
            assertThat(row.subList(1, 3)).allSatisfy(b -> assertThat(b.callbackData().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64));
        });
    }

    @Test
    void withAPrepLinkApplyAndPrepGetTheirOwnRow() {
        var keyboard = new AlertFormatter("https://prep.example.com/dashboard?role={title}&company={company}&skills={skills}")
                .keyboard(delivery("Backend Engineer I", List.of(), "onsite", null, null, List.of(), List.of()));

        List<InlineKeyboardButton> links = keyboard.inlineKeyboard().get(0);
        assertThat(links).extracting(InlineKeyboardButton::text).containsExactly("Apply ↗", "Prep ↗");
        assertThat(links.get(1).url()).isEqualTo("https://prep.example.com/dashboard?role=Backend+Engineer+I"
                + "&company=ExampleCo&skills=Java%2CSpring+Boot%2CPostgreSQL%2CKafka%2CDocker");
        List<InlineKeyboardButton> feedback = keyboard.inlineKeyboard().get(1);
        assertThat(feedback).extracting(InlineKeyboardButton::callbackData).allMatch(c -> c.startsWith("fb:"));
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

        assertThat(text).isEqualTo("""
                <b>Your evening digest</b>
                2 jobs from today that scored just below your alert threshold.

                <b><a href="https://x.com/a?b=1&amp;c=&quot;2&quot;">Backend Engineer</a></b>
                Acme · 62% match

                <b><a href="https://y.com">Data Engineer</a></b>
                Beta · 48% match

                <i>Change your threshold with /prefs</i>""");
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
            assertThat(HtmlBalance.unbalanced(m)).as(m).isEmpty();
            assertThat(m.length()).isLessThanOrEqualTo(4096);
        });
    }
}
