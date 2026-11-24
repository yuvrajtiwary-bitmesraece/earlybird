package io.github.yuvrajtiwary.earlybird.events;

import java.util.List;
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
        String tagger) {}
