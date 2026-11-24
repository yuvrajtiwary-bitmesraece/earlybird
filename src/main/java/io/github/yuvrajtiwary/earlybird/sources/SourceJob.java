package io.github.yuvrajtiwary.earlybird.sources;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One job as a board lists it, normalized across Greenhouse, Lever and Ashby.
 *
 * @param publishedAt only set when the source's publish time is trustworthy (Greenhouse {@code first_published},
 *                    Ashby {@code publishedAt}); Lever has none, so it's null there
 * @param description plain text, cut to {@value #MAX_DESCRIPTION} characters; null if not fetched yet
 *                    (Greenhouse lists don't include it)
 */
public record SourceJob(
        String externalId,
        String title,
        String url,
        String locationRaw,
        List<String> departments,
        Instant publishedAt,
        String description) {

    /** Longest description kept: tagging reads 2,500 characters and events carry at most 3,000. */
    public static final int MAX_DESCRIPTION = 3000;

    public SourceJob {
        Objects.requireNonNull(externalId, "externalId");
        title = title == null ? "" : title.strip().replaceAll("\\s+", " ");
        locationRaw = locationRaw == null || locationRaw.isBlank() ? null : locationRaw.strip();
        departments = departments == null ? List.of()
                : departments.stream().filter(d -> d != null && !d.isBlank()).map(String::strip).distinct().toList();
        description = description == null ? null : HtmlText.truncate(description, MAX_DESCRIPTION);
    }

    public SourceJob withDetails(String newDescription, List<String> newDepartments) {
        return new SourceJob(externalId, title, url, locationRaw,
                newDepartments == null || newDepartments.isEmpty() ? departments : newDepartments,
                publishedAt, newDescription);
    }

    /**
     * Builds a readable location such as {@code "Bengaluru / Pune, IN · Hybrid"} from a source's parts. The work
     * mode is added only when the source states it and the place names don't already say it.
     */
    static String formatLocation(List<String> places, String country, String workplace) {
        List<String> parts = new ArrayList<>();
        if (places != null) {
            places.stream().filter(p -> p != null && !p.isBlank()).map(String::strip).distinct().forEach(parts::add);
        }
        String text = String.join(" / ", parts);
        // Whole-word check: "India" must not count as already containing the country code "IN".
        if (country != null && !country.isBlank()
                && !Pattern.compile("\\b" + Pattern.quote(country.strip()) + "\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(text).find()) {
            text = text.isEmpty() ? country.strip() : text + ", " + country.strip();
        }
        String mode = workMode(workplace);
        if (mode != null && !text.toLowerCase(Locale.ROOT).contains(mode.toLowerCase(Locale.ROOT))) {
            text = text.isEmpty() ? mode : text + " · " + mode;
        }
        return text.isEmpty() ? null : text;
    }

    private static String workMode(String workplace) {
        if (workplace == null) {
            return null;
        }
        return switch (workplace.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")) {
            case "remote" -> "Remote";
            case "hybrid" -> "Hybrid";
            case "onsite" -> "On-site";
            default -> null;
        };
    }
}
