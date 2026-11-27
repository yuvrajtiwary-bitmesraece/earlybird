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
 * Alert and digest messages (spec 3.10, redesigned in issue #10), in Telegram HTML. Every piece of text that came
 * from a job board or a model is escaped; we never send a full job description, only a link to the original posting.
 */
public class AlertFormatter {

    /** Logos come from Google's public favicon service: no key, and an unknown domain just gets no preview. */
    static final String LOGO_URL = "https://www.google.com/s2/favicons?sz=128&domain=";

    private final String prepLinkTemplate;

    public AlertFormatter(String prepLinkTemplate) {
        this.prepLinkTemplate = prepLinkTemplate == null ? "" : prepLinkTemplate.strip();
    }

    /**
     * Laid out to be read at a glance on a phone: how good the match is, what the job is, where, then the skills
     * in a quote block. The company's logo shows beside it as a small link preview ({@link #logoUrl}).
     * <pre>
     * 🟢 Strong match
     * ▰▰▰▰▰▰▰▰▱▱  78/100
     *
     * Backend Engineer I
     * 🏢 ExampleCo
     *
     * 📍 Bengaluru · Hybrid
     * 🎓 0–2 yrs  ·  ⏱ found 2 min ago
     *
     * ┃ ✅ You have
     * ┃ Java · Spring Boot · PostgreSQL
     * ┃
     * ┃ 📚 Worth learning
     * ┃ Kafka · Docker
     * </pre>
     */
    public String alert(Delivery d, boolean recent, Instant now) {
        var a = d.alert();
        StringBuilder text = new StringBuilder()
                .append(tierEmoji(a.score())).append(" <b>").append(tier(a.score())).append("</b>")
                .append(recent ? " · <i>from the last 2 days</i>" : "").append('\n')
                .append(bar(a.score())).append("  ").append(a.score()).append("/100\n\n")
                .append("<b>").append(escape(d.title())).append("</b>\n")
                .append("🏢 ").append(escape(d.company())).append("\n\n")
                .append("📍 ").append(escape(place(d))).append('\n');
        String years = years(d.minYears(), d.maxYears());
        if (years != null) {
            text.append("🎓 ").append(years).append("  ·  ");
        }
        text.append("⏱ found ").append(ago(d.firstSeenAt(), now));

        List<String> skills = new ArrayList<>();
        if (!a.matchedSkills().isEmpty()) {
            skills.add("✅ <b>You have</b>\n" + list(a.matchedSkills()));
        }
        if (!a.missingSkills().isEmpty()) {
            skills.add("📚 <b>Worth learning</b>\n" + list(a.missingSkills()));
        }
        if (d.jobSkills().isEmpty()) {
            skills.add("<i>The posting doesn't list specific skills.</i>");
        }
        if (!skills.isEmpty()) {
            text.append("\n\n<blockquote>").append(String.join("\n\n", skills)).append("</blockquote>");
        }
        return text.toString();
    }

    /** The company's logo for the message's small link preview, or null to send without one. */
    public String logoUrl(Delivery d) {
        return d.companyDomain() == null || d.companyDomain().isBlank() ? null : LOGO_URL + d.companyDomain();
    }

    static String tier(int score) {
        return score >= 85 ? "Excellent match" : score >= 70 ? "Strong match" : "Good match";
    }

    static String tierEmoji(int score) {
        return score >= 85 ? "🔥" : score >= 70 ? "🟢" : "🔵";
    }

    /** Ten cells, one per 10 points, rounded: 78 → ▰▰▰▰▰▰▰▰▱▱. */
    static String bar(int score) {
        int filled = Math.clamp(Math.round(score / 10f), 0, 10);
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }

    private static String list(List<String> skills) {
        return escape(String.join(" · ", skills));
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
                InlineKeyboardButton.callback("👎 Not for me", "fb:n:" + id))));
    }

    /**
     * The 20:00 digest: one message, best first, each title linking to the posting.
     * <pre>
     * 🌙 Your evening digest
     * 2 jobs from the last 24 hours, best first
     *
     * 1. Backend Engineer
     * 🏢 Acme  ·  🔵 62/100
     * </pre>
     */
    public String digest(List<DigestItem> items) {
        StringBuilder text = new StringBuilder("🌙 <b>Your evening digest</b>\n")
                .append(items.size()).append(items.size() == 1 ? " job" : " jobs")
                .append(" from the last 24 hours, best first\n");
        int i = 1;
        for (DigestItem item : items) {
            text.append("\n<b>").append(i++).append(".</b> <a href=\"").append(escape(item.url())).append("\">")
                    .append(escape(item.title())).append("</a>\n")
                    .append("🏢 ").append(escape(item.company())).append("  ·  ")
                    .append(tierEmoji(item.score())).append(' ').append(item.score()).append("/100\n");
        }
        return text.append("\n<i>These scored below your instant-alert threshold, or came after your daily cap.</i>\n")
                .append("⚙️ Change that with /prefs").toString();
    }

    /** "Bengaluru · Hybrid": the cities we tagged (at most 3), else the board's own text, plus the work mode. */
    static String place(Delivery d) {
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
