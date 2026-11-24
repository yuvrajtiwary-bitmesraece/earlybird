package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A row of {@code posting_tags}: the facts tagging extracted from a posting (spec section 3.7). */
public record PostingTags(
        UUID postingId,
        RoleCategory roleCategory,
        Seniority seniority,
        Double minYears,
        Double maxYears,
        boolean fresherFriendly,
        EmploymentType employmentType,
        WorkMode workMode,
        RemoteScope remoteScope,
        List<String> cities,
        List<String> countries,
        List<String> skills,
        String summary,
        Tagger tagger,
        String model,
        Instant enrichedAt) {

    public PostingTags {
        cities = cities == null ? List.of() : List.copyOf(cities);
        countries = countries == null ? List.of() : List.copyOf(countries);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public enum Seniority { INTERN, NEW_GRAD, JUNIOR, MID, SENIOR, STAFF_PLUS, UNKNOWN }

    public enum EmploymentType { FULL_TIME, INTERNSHIP, CONTRACT, PART_TIME, UNKNOWN }

    public enum WorkMode { REMOTE, HYBRID, ONSITE, UNKNOWN }

    public enum RemoteScope { WORLDWIDE, COUNTRY, REGION, UNKNOWN }

    /** Who produced the tags: Gemini, or the deterministic keyword tagger used when Gemini isn't available. */
    public enum Tagger { GEMINI, FALLBACK }
}
