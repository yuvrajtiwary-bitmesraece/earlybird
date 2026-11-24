package io.github.yuvrajtiwary.earlybird.alerts;

import static io.github.yuvrajtiwary.earlybird.common.Html.escape;

import io.github.yuvrajtiwary.earlybird.common.Base62;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.Delivery;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.DigestItem;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardButton;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardMarkup;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Alert and digest messages (spec 3.10), in Telegram HTML. Every piece of text that came from a job board or a
 * model is escaped; we never send a full job description, only a link to the original posting.
 */
public class AlertFormatter {

    private final String prepLinkTemplate;

    public AlertFormatter(String prepLinkTemplate) {
        this.prepLinkTemplate = prepLinkTemplate == null ? "" : prepLinkTemplate.strip();
    }

    /**
     * <pre>
     * 🐦 New match · 78/100
     * <b>Backend Engineer I</b> — ExampleCo
     * 📍 Bengaluru · Hybrid · 0–2 yrs · found 2 min ago
     * ✅ You have: Java, Spring Boot, PostgreSQL
     * ➕ Missing: Kafka, Docker
     * </pre>
     */
    public String alert(Delivery d, boolean recent, Instant now) {
        var a = d.alert();
        StringBuilder text = new StringBuilder()
                .append(recent ? "🕊 Recent match · " : "🐦 New match · ").append(a.score()).append("/100\n")
                .append("<b>").append(escape(d.title())).append("</b> — ").append(escape(d.company())).append("\n")
                .append("📍 ").append(escape(where(d))).append(" · found ").append(ago(d.firstSeenAt(), now)).append("\n");
        if (!a.matchedSkills().isEmpty()) {
            text.append("✅ You have: ").append(escape(String.join(", ", a.matchedSkills()))).append("\n");
        }
        if (!a.missingSkills().isEmpty()) {
            text.append("➕ Missing: ").append(escape(String.join(", ", a.missingSkills()))).append("\n");
        }
        if (d.jobSkills().isEmpty()) {
            text.append("The posting doesn't list specific skills.\n");
        }
        return text.toString().strip();
    }

    /** Row 1: Apply ↗ and, if configured, Prep ↗. Row 2: 👍 / 👎 feedback. */
    public InlineKeyboardMarkup keyboard(Delivery d) {
        List<InlineKeyboardButton> links = new ArrayList<>();
        links.add(InlineKeyboardButton.link("Apply ↗", d.url()));
        if (!prepLinkTemplate.isEmpty()) {
            List<String> skills = d.jobSkills().size() > 6 ? d.jobSkills().subList(0, 6) : d.jobSkills();
            links.add(InlineKeyboardButton.link("Prep for this job ↗", prepLinkTemplate
                    .replace("{title}", encode(d.title()))
                    .replace("{company}", encode(d.company()))
                    .replace("{skills}", encode(String.join(",", skills)))));
        }
        String id = Base62.encode(d.alert().id());
        return new InlineKeyboardMarkup(List.of(links, List.of(
                InlineKeyboardButton.callback("👍 Relevant", "fb:r:" + id),
                InlineKeyboardButton.callback("👎 Not relevant", "fb:n:" + id))));
    }

    /** The 20:00 digest: one message, best first, each title linking to the posting. */
    public String digest(List<DigestItem> items) {
        StringBuilder text = new StringBuilder("🌙 <b>Your EarlyBird digest</b> · ")
                .append(items.size()).append(items.size() == 1 ? " job" : " jobs").append(" from the last 24 hours\n\n");
        int i = 1;
        for (DigestItem item : items) {
            text.append(i++).append(". <a href=\"").append(escape(item.url())).append("\">").append(escape(item.title()))
                    .append("</a> — ").append(escape(item.company())).append(" · ").append(item.score()).append("/100\n");
        }
        return text.append("\nThese scored below your instant-alert threshold (or came after your daily cap). /prefs to change it.")
                .toString();
    }

    /** "Bengaluru · Hybrid · 0–2 yrs". */
    static String where(Delivery d) {
        List<String> parts = new ArrayList<>();
        String place = d.cities().isEmpty() ? d.locationRaw() : String.join(", ", d.cities().subList(0, Math.min(3, d.cities().size())));
        if (place != null && !place.isBlank()) {
            parts.add(place);
        }
        String mode = switch (d.workMode() == null ? "unknown" : d.workMode()) {
            case "remote" -> "Remote";
            case "hybrid" -> "Hybrid";
            case "onsite" -> "On-site";
            default -> null;
        };
        if (mode != null && (place == null || !place.toLowerCase(Locale.ROOT).contains(mode.toLowerCase(Locale.ROOT)))) {
            parts.add(mode);
        }
        String years = years(d.minYears(), d.maxYears());
        if (years != null) {
            parts.add(years);
        }
        return parts.isEmpty() ? "Location not stated" : String.join(" · ", parts);
    }

    static String years(Double min, Double max) {
        if (min == null) {
            return max == null ? null : "up to " + fmt(max) + " yrs";
        }
        return max == null ? fmt(min) + "+ yrs" : fmt(min) + "–" + fmt(max) + " yrs";
    }

    static String ago(Instant then, Instant now) {
        long minutes = Math.max(0, Duration.between(then, now).toMinutes());
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " h ago" : hours / 24 + " days ago";
    }

    private static String fmt(double years) {
        return years == Math.floor(years) ? String.valueOf((long) years) : String.valueOf(years);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
