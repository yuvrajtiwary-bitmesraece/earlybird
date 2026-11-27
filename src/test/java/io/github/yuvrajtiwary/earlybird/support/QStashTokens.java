package io.github.yuvrajtiwary.earlybird.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Builds QStash-style signatures for tests, independently of the verifier: an HS256 JWT whose claims are those
 * QStash documents (iss, sub, exp, nbf, iat, body = base64url SHA-256 of the body).
 */
public final class QStashTokens {

    public static final String CURRENT_KEY = "sig_test_current_0123456789abcdef";
    public static final String NEXT_KEY = "sig_test_next_0123456789abcdef";

    private QStashTokens() {}

    public static String sign(String key, String url, String body, Instant now) {
        return sign(key, "HS256", "Upstash", url, bodyHash(body), now.getEpochSecond() + 300, now.getEpochSecond());
    }

    public static String sign(String key, String alg, String iss, String sub, String bodyClaim, long exp, long nbf) {
        String header = b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}");
        String claims = b64("{\"iss\":\"" + iss + "\",\"sub\":\"" + sub + "\",\"exp\":" + exp + ",\"nbf\":" + nbf
                + ",\"iat\":" + nbf + ",\"jti\":\"jwt_test\",\"body\":\"" + bodyClaim + "\"}");
        String signed = header + "." + claims;
        return signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, signed));
    }

    /** As QStash sends it: base64url with padding. */
    public static String bodyHash(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
