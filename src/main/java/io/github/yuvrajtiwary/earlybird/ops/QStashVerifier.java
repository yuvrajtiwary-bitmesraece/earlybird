package io.github.yuvrajtiwary.earlybird.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the {@code Upstash-Signature} header QStash puts on every scheduled call (spec 3.3; QStash docs, "Verify
 * signatures"). The header is a JWT signed with HS256, the key being the signing key's text. A request is
 * accepted only if all of this holds:
 * <ul>
 *   <li>the signature matches the current <em>or</em> the next signing key (so keys can be rotated with no gap);
 *   <li>{@code iss} is {@code Upstash};
 *   <li>{@code sub} is exactly the URL QStash was told to call, so a token for one endpoint can't be replayed
 *       against another;
 *   <li>{@code exp} hasn't passed and {@code nbf} has (QStash tokens live 5 minutes); 10 s of clock skew allowed;
 *   <li>{@code body} is the base64url SHA-256 of the raw request body (padding ignored, as in QStash's SDK).
 * </ul>
 * Written by hand on purpose: it's 100 lines, and a JWT library would bring options (other algorithms, "none")
 * that only widen what's accepted.
 */
public final class QStashVerifier {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(10);
    private static final String ISSUER = "Upstash";

    private final List<byte[]> keys;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public QStashVerifier(String currentSigningKey, String nextSigningKey, Clock clock) {
        this.keys = java.util.stream.Stream.of(currentSigningKey, nextSigningKey)
                .filter(k -> k != null && !k.isBlank())
                .map(k -> k.strip().getBytes(StandardCharsets.UTF_8))
                .toList();
        this.clock = clock;
    }

    /** Why a request was refused; never includes the token or keys. */
    public static final class Rejected extends Exception {
        Rejected(String reason) {
            super(reason, null, false, false);
        }
    }

    public void verify(String token, byte[] body, String url) throws Rejected {
        if (keys.isEmpty()) {
            throw new Rejected("no signing keys configured");
        }
        if (token == null || token.isBlank()) {
            throw new Rejected("missing Upstash-Signature");
        }
        String[] parts = token.strip().split("\\.", -1);
        if (parts.length != 3) {
            throw new Rejected("not a JWT");
        }
        JsonNode header = decode(parts[0]);
        if (!"HS256".equals(text(header, "alg"))) {
            throw new Rejected("algorithm must be HS256");
        }
        byte[] signature = base64Url(parts[2]);
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (keys.stream().noneMatch(key -> MessageDigest.isEqual(hmac(key, signed), signature))) {
            throw new Rejected("bad signature");
        }

        JsonNode claims = decode(parts[1]);
        long now = clock.instant().getEpochSecond();
        if (!ISSUER.equals(text(claims, "iss"))) {
            throw new Rejected("wrong issuer");
        }
        if (!Objects.equals(url, text(claims, "sub"))) {
            throw new Rejected("wrong subject (URL)");
        }
        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.canConvertToLong() || now > exp.asLong() + CLOCK_SKEW.toSeconds()) {
            throw new Rejected("expired");
        }
        JsonNode nbf = claims.get("nbf");
        if (nbf != null && nbf.canConvertToLong() && now + CLOCK_SKEW.toSeconds() < nbf.asLong()) {
            throw new Rejected("not valid yet");
        }
        String bodyClaim = text(claims, "body");
        if (bodyClaim == null || !stripPadding(bodyClaim).equals(stripPadding(bodyHash(body)))) {
            throw new Rejected("body hash doesn't match");
        }
    }

    static String bodyHash(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode decode(String part) throws Rejected {
        try {
            JsonNode node = json.readTree(base64Url(part));
            if (node == null || !node.isObject()) {
                throw new Rejected("malformed JWT");
            }
            return node;
        } catch (JacksonException e) {
            throw new Rejected("malformed JWT");
        }
    }

    private static byte[] base64Url(String part) throws Rejected {
        try {
            return Base64.getUrlDecoder().decode(stripPadding(part));
        } catch (IllegalArgumentException e) {
            throw new Rejected("malformed JWT");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static String stripPadding(String s) {
        return s.replaceAll("=+$", "");
    }
}
