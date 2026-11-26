package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A posting was tagged (topic {@code eb.jobs.enriched.v1}, key = postingId). Spec 3.2: the posting id and its
 * tags, so other consumers (e.g. a skills-trend service) don't need our database. The matcher re-reads the
 * row anyway, since the database is the source of truth.
 */
public record PostingEnriched(
        UUID postingId,
        String roleCategory,
        String seniority,
        Double minYears,
        Double maxYears,
        boolean fresherFriendly,
        String employmentType,
        String workMode,
        String remoteScope,
        List<String> cities,
        List<String> countries,
        List<String> skills,
        String tagger) {

    public static PostingEnriched of(PostingTags t) {
        return new PostingEnriched(t.postingId(), db(t.roleCategory()), db(t.seniority()), t.minYears(),
                t.maxYears(), t.fresherFriendly(), db(t.employmentType()), db(t.workMode()), db(t.remoteScope()),
                t.cities(), t.countries(), t.skills(), db(t.tagger()));
    }

    /** Enum values as stored in the database: lowercase. */
    private static String db(Enum<?> e) {
        return e == null ? null : e.name().toLowerCase(Locale.ROOT);
    }
}
