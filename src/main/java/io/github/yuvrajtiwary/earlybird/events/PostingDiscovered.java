package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A new posting to tag (topic {@code eb.jobs.discovered.v1}, key = postingId). Spec section 3.2.
 *
 * @param source            lowercase platform name, e.g. {@code greenhouse}
 * @param sourcePublishedAt null unless the platform's publish time is trustworthy
 * @param description       plain text, at most 3,000 characters and 6,000 UTF-8 bytes, so the message stays under
 *                          8 KB even for non-Latin text. The enricher reads the full text from the database.
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
        boolean baseline) {

    /** Descriptions are cut to this many UTF-8 bytes in the event (spec 3.2: messages under 8 KB). */
    static final int MAX_DESCRIPTION_BYTES = 6_000;

    public static PostingDiscovered of(Posting p, String boardToken) {
        return new PostingDiscovered(p.id(), p.source().name().toLowerCase(Locale.ROOT), boardToken, p.company(),
                p.externalId(), p.title(), p.url(), p.locationRaw(), p.departments(), p.sourcePublishedAt(),
                p.firstSeenAt(), p.contentHash(), fitting(p.descriptionText(), MAX_DESCRIPTION_BYTES), p.baseline());
    }

    /** The longest prefix of {@code text} that fits in {@code maxBytes} of UTF-8, never splitting a character. */
    static String fitting(String text, int maxBytes) {
        if (text == null) {
            return null;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
                    .decode(ByteBuffer.wrap(bytes, 0, maxBytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e); // can't happen: malformed input is ignored
        }
    }
}
