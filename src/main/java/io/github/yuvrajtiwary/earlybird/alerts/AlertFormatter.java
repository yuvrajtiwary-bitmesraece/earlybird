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
import java.util.Set;

/**
 * Alert and digest messages (spec 3.10, redesigned in issue #10), in Telegram HTML. Every piece of text that came
 * from a job board or a model is escaped; we never send a full job description, only a link to the original posting.
 *
 * <p>The style is deliberately quiet: no emoji in the text, the title in bold, then plain lines. The company's logo
 * card (see {@link #logoUrl}) sits above the text and carries the visual weight.
 */
public class AlertFormatter {

    /**
     * Bumped whenever the logo cards are regenerated: Telegram caches a link preview per URL, so a new query string
     * is the only way to make it fetch the new picture.
     */
    static final String LOGO_VERSION = "1";

    private final String prepLinkTemplate;
    private final String logoBaseUrl;
    private final Set<String> logoDomains;

    /**
     * @param logoBaseUrl where the cards are served, e.g. {@code https://earlybird.onrender.com/logos/}
     * @param logoDomains the domains that have a card; any other company's alert is sent without a picture
     */
    public AlertFormatter(String prepLinkTemplate, String logoBaseUrl, Set<String> logoDomains) {
        this.prepLinkTemplate = prepLinkTemplate == null ? "" : prepLinkTemplate.strip();
        this.logoBaseUrl = logoBaseUrl;
        this.logoDomains = Set.copyOf(logoDomains);
    }

    /** Without logos (tests, and local runs where Telegram couldn't reach the cards anyway). */
    public AlertFormatter(String prepLinkTemplate) {
        this(prepLinkTemplate, "", Set.of());
    }

    /**
     * <pre>
     * Backend Engineer I
     * Stripe · Bengaluru · Hybrid
     *
     * 78% match · 0–2 yrs · 2 min ago
     * You have: Java, Spring Boot, PostgreSQL
     * To learn: Kafka, Docker
     * </pre>
     */
    public String alert(Delivery d, boolean recent, Instant now) {
        var a = d.alert();
        StringBuilder text = new StringBuilder()
                .append("<b>").append(escape(d.title())).append("</b>\n")
                .append(escape(d.company())).append(" · ").append(escape(place(d))).append("\n\n");

        List<String> facts = new ArrayList<>();
        facts.add(a.score() + "% match");
        String years = years(d.minYears(), d.maxYears());
        if (years != null) {
            facts.add(years);
        }
        facts.add(ago(d.firstSeenAt(), now));
        text.append(String.join(" · ", facts));

        if (!a.matchedSkills().isEmpty()) {
            text.append("\nYou have: ").append(escape(String.join(", ", a.matchedSkills())));
        }
        if (!a.missingSkills().isEmpty()) {
            text.append("\nTo learn: ").append(escape(String.join(", ", a.missingSkills())));
        }
        if (d.jobSkills().isEmpty()) {
            text.append("\nThe posting doesn't list specific skills.");
        }
        if (recent) {
            text.append("\n\n<i>A recent match from the last 2 days</i>");
        }
        return text.toString();
    }

    /** The company's logo card, shown above the alert, or null to send the alert without a picture. */
    public String logoUrl(Delivery d) {
        String domain = d.companyDomain();
        return domain == null || !logoDomains.contains(domain) ? null : logoBaseUrl + domain + ".png?v=" + LOGO_VERSION;
    }

    /** One row: Apply ↗, 👍, 👎. With a prep link configured, Apply and Prep get their own row above the feedback. */
    public InlineKeyboardMarkup keyboard(Delivery d) {
        String id = Base62.encode(d.alert().id());
        InlineKeyboardButton apply = InlineKeyboardButton.link("Apply ↗", d.url());
        InlineKeyboardButton relevant = InlineKeyboardButton.callback("👍", "fb:r:" + id);
        InlineKeyboardButton notRelevant = InlineKeyboardButton.callback("👎", "fb:n:" + id);
        if (prepLinkTemplate.isEmpty()) {
            return new InlineKeyboardMarkup(List.of(List.of(apply, relevant, notRelevant)));
        }
        List<String> skills = d.jobSkills().size() > 6 ? d.jobSkills().subList(0, 6) : d.jobSkills();
        InlineKeyboardButton prep = InlineKeyboardButton.link("Prep ↗", prepLinkTemplate
                .replace("{title}", encode(d.title()))
                .replace("{company}", encode(d.company()))
                .replace("{skills}", encode(String.join(",", skills))));
        return new InlineKeyboardMarkup(List.of(List.of(apply, prep), List.of(relevant, notRelevant)));
    }

    /**
     * The 20:00 digest: one message, best first, each title linking to the posting.
     * <pre>
     * Your evening digest
     * 2 jobs from today that scored just below your alert threshold.
     *
     * Data Engineer
     * Databricks · 64% match
     * </pre>
     */
    public String digest(List<DigestItem> items) {
        StringBuilder text = new StringBuilder("<b>Your evening digest</b>\n")
                .append(items.size()).append(items.size() == 1 ? " job" : " jobs")
                .append(" from today that scored just below your alert threshold.\n");
        for (DigestItem item : items) {
            text.append("\n<b><a href=\"").append(escape(item.url())).append("\">").append(escape(item.title()))
                    .append("</a></b>\n")
                    .append(escape(item.company())).append(" · ").append(item.score()).append("% match\n");
        }
        return text.append("\n<i>Change your threshold with /prefs</i>").toString();
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
