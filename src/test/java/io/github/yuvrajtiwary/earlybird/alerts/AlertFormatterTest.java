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
                List.of("Java", "Spring Boot", "PostgreSQL", "Kafka", "Docker"));
    }

    @Test
    void matchesTheSpecsLayout() {
        Delivery d = delivery("Backend Engineer I", List.of("Bengaluru"), "hybrid", 0.0, 2.0,
                List.of("Java", "Spring Boot", "PostgreSQL"), List.of("Kafka", "Docker"));

        assertThat(new AlertFormatter("").alert(d, false, NOW)).isEqualTo("""
                🐦 New match · 78/100
                <b>Backend Engineer I</b> — ExampleCo
                📍 Bengaluru · Hybrid · 0–2 yrs · found 2 min ago
                ✅ You have: Java, Spring Boot, PostgreSQL
                ➕ Missing: Kafka, Docker""");
    }

    @Test
    void recentMatchesAreLabelledAndTextIsEscaped() {
        Delivery d = delivery("<script>R&D</script>", List.of(), "remote", 3.0, null, List.of(), List.of());

        String text = new AlertFormatter("").alert(d, true, NOW);

        assertThat(text).startsWith("🕊 Recent match · 78/100")
                .contains("&lt;script&gt;R&amp;D&lt;/script&gt;")
                .contains("📍 Bengaluru, India · Remote · 3+ yrs");
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
        assertThat(feedback).extracting(InlineKeyboardButton::text).containsExactly("👍 Relevant", "👎 Not relevant");
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

        assertThat(text).contains("2 jobs", "1. <a href=\"https://x.com/a?b=1&amp;c=&quot;2&quot;\">Backend Engineer</a> — Acme · 62/100",
                "2. <a href=\"https://y.com\">Data Engineer</a> — Beta · 48/100");
    }
}
