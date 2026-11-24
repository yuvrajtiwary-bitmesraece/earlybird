package io.github.yuvrajtiwary.earlybird.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A new posting to tag (topic {@code eb.jobs.discovered.v1}, key = postingId). Spec section 3.2.
 *
 * @param source            lowercase platform name, e.g. {@code greenhouse}
 * @param sourcePublishedAt null unless the platform's publish time is trustworthy
 * @param description       plain text, at most 3,000 characters, so the message stays well under 8 KB
 * @param baseline          found on the board's first check (only sent if published in the last 48 h); the
 *                          matcher may offer it in "recent matches" and the digest, never as an instant alert.
 *                          Not in the spec's payload list; added with the baseline rule.
 */
public record PostingDiscovered(
        UUID postingId,
        String source,
        String boardToken,
        String company,
        String externalId,
        String title,
        String url,
        String locationRaw,
        List<String> departments,
        Instant sourcePublishedAt,
        Instant firstSeenAt,
        String contentHash,
        String description,
        boolean baseline) {}
