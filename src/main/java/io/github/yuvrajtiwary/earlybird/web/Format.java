package io.github.yuvrajtiwary.earlybird.web;

import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Text for the pages: times in IST (stored in UTC), durations, and labels for stored enum values. */
final class Format {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm 'IST'", Locale.ENGLISH).withZone(IST);

    /** Filter labels for /live, in the bot's order. */
    static final Map<String, String> ROLES = roles();

    private static Map<String, String> roles() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("backend", "Backend");
        m.put("frontend", "Frontend");
        m.put("fullstack", "Full-stack");
        m.put("mobile", "Mobile");
        m.put("data_engineering", "Data engineering");
        m.put("data_science_ml", "Data science / ML");
        m.put("devops_sre_cloud", "DevOps / SRE / Cloud");
        m.put("qa_test", "QA / Test");
        m.put("security", "Security");
        m.put("embedded", "Embedded");
        return java.util.Collections.unmodifiableMap(m);
    }

    private Format() {}

    /** The database value of a role from {@code ?role=}, if it is one; anything else means "all roles". */
    static Optional<String> role(String param) {
        if (param == null) {
            return Optional.empty();
        }
        String value = param.strip().toLowerCase(Locale.ROOT);
        for (RoleCategory r : RoleCategory.values()) {
            if (r != RoleCategory.OTHER && r.name().toLowerCase(Locale.ROOT).equals(value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    static String when(Instant instant) {
        return instant == null ? "never" : WHEN.format(instant);
    }

    /** "just now", "4 min ago", "3 h ago", "2 days ago". */
    static String ago(Instant then, Instant now) {
        long minutes = Duration.between(then, now).toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        if (minutes < 48 * 60) {
            return minutes / 60 + " h ago";
        }
        return minutes / (24 * 60) + " days ago";
    }

    /** Seconds as "42 s", "3.5 min" or "2.1 h"; a dash when unknown. */
    static String seconds(Double seconds) {
        if (seconds == null) {
            return "–";
        }
        if (seconds < 90) {
            return Math.round(seconds) + " s";
        }
        if (seconds < 90 * 60) {
            return String.format(Locale.ROOT, "%.1f min", seconds / 60);
        }
        return String.format(Locale.ROOT, "%.1f h", seconds / 3600);
    }

    /** "Junior · Hybrid · 0–2 yrs" from whatever tags exist. */
    static String tags(String seniority, String workMode, Double minYears, Double maxYears) {
        StringBuilder out = new StringBuilder();
        append(out, label(seniority));
        append(out, label(workMode));
        if (minYears != null) {
            String years = trim(minYears) + (maxYears != null && !maxYears.equals(minYears) ? "–" + trim(maxYears) : "+");
            append(out, years + " yrs");
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String part) {
        if (part != null && !part.isEmpty()) {
            out.append(out.isEmpty() ? "" : " · ").append(part);
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** "new_grad" → "New grad"; "unknown" and null → nothing. */
    static String label(String value) {
        if (value == null || value.equals("unknown") || value.equals("other")) {
            return "";
        }
        String spaced = value.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
