package io.github.yuvrajtiwary.earlybird.sources;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fingerprint of what a posting says, ignoring the board's id for it (spec section 3.6):
 * {@code sha256(company | title | location | first 1,000 characters of the description)}, all lowercased and
 * with whitespace collapsed. When a company closes a job and reposts it under a new id to bump it to the top,
 * the id changes but the fingerprint doesn't, so the repost isn't alerted as new.
 */
public final class ContentHash {

    static final int DESCRIPTION_PREFIX = 1000;

    private ContentHash() {}

    public static String of(String company, String title, String location, String description) {
        String desc = normalize(description);
        if (desc.length() > DESCRIPTION_PREFIX) {
            desc = desc.substring(0, DESCRIPTION_PREFIX);
        }
        String input = normalize(company) + "|" + normalize(title) + "|" + normalize(location) + "|" + desc;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
