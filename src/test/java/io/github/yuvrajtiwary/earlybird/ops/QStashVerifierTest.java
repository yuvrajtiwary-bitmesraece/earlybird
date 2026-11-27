package io.github.yuvrajtiwary.earlybird.ops;

import static io.github.yuvrajtiwary.earlybird.support.QStashTokens.CURRENT_KEY;
import static io.github.yuvrajtiwary.earlybird.support.QStashTokens.NEXT_KEY;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.support.MutableClock;
import io.github.yuvrajtiwary.earlybird.support.QStashTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QStashVerifierTest {

    private static final String URL = "https://earlybird.onrender.com/internal/tick";
    private static final String BODY = "{\"run\":\"tick\"}";
    private static final Instant NOW = Instant.parse("2026-11-27T08:30:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final QStashVerifier verifier = new QStashVerifier(CURRENT_KEY, NEXT_KEY, clock);

    private void verify(String token, String body, String url) throws QStashVerifier.Rejected {
        verifier.verify(token, body.getBytes(StandardCharsets.UTF_8), url);
    }

    @Test
    void acceptsATokenSignedWithEitherKey() {
        assertThatCode(() -> verify(QStashTokens.sign(CURRENT_KEY, URL, BODY, NOW), BODY, URL)).doesNotThrowAnyException();
        // During key rotation QStash signs with the next key; both must work.
        assertThatCode(() -> verify(QStashTokens.sign(NEXT_KEY, URL, BODY, NOW), BODY, URL)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnEmptyBody() {
        assertThatCode(() -> verify(QStashTokens.sign(CURRENT_KEY, URL, "", NOW), "", URL)).doesNotThrowAnyException();
    }

    @Test
    void refusesAnyOtherKey() {
        String forged = QStashTokens.sign("sig_someone_else", URL, BODY, NOW);
        assertThatThrownBy(() -> verify(forged, BODY, URL)).hasMessage("bad signature");
    }

    /** A token for /internal/digest can't start a tick: the subject is the exact URL. */
    @Test
    void refusesATokenForAnotherUrl() {
        String digest = QStashTokens.sign(CURRENT_KEY, "https://earlybird.onrender.com/internal/digest", BODY, NOW);
        assertThatThrownBy(() -> verify(digest, BODY, URL)).hasMessage("wrong subject (URL)");
    }

    @Test
    void refusesAChangedBody() {
        String token = QStashTokens.sign(CURRENT_KEY, URL, BODY, NOW);
        assertThatThrownBy(() -> verify(token, "{\"run\":\"evil\"}", URL)).hasMessage("body hash doesn't match");
    }

    @Test
    void refusesExpiredAndNotYetValidTokens() {
        String token = QStashTokens.sign(CURRENT_KEY, URL, BODY, NOW); // exp = now + 5 min
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> verify(token, BODY, URL)).hasMessage("expired");

        long later = NOW.getEpochSecond() + 3600;
        String future = QStashTokens.sign(CURRENT_KEY, "HS256", "Upstash", URL, QStashTokens.bodyHash(BODY),
                later + 300, later);
        assertThatThrownBy(() -> verify(future, BODY, URL)).hasMessage("not valid yet");
    }

    @Test
    void refusesAnotherIssuerOrAlgorithm() {
        long now = NOW.getEpochSecond();
        String issuer = QStashTokens.sign(CURRENT_KEY, "HS256", "Someone", URL, QStashTokens.bodyHash(BODY), now + 300, now);
        assertThatThrownBy(() -> verify(issuer, BODY, URL)).hasMessage("wrong issuer");
        // The classic JWT attack: "alg": "none" with no signature.
        String none = QStashTokens.sign(CURRENT_KEY, "none", "Upstash", URL, QStashTokens.bodyHash(BODY), now + 300, now);
        assertThatThrownBy(() -> verify(none.substring(0, none.lastIndexOf('.') + 1), BODY, URL))
                .hasMessage("algorithm must be HS256");
    }

    @Test
    void refusesMissingOrGarbledHeaders() {
        assertThatThrownBy(() -> verify(null, BODY, URL)).hasMessage("missing Upstash-Signature");
        assertThatThrownBy(() -> verify("abc", BODY, URL)).hasMessage("not a JWT");
        assertThatThrownBy(() -> verify("a.b.c", BODY, URL)).hasMessage("malformed JWT");
    }

    @Test
    void withoutKeysNothingIsAccepted() {
        var unconfigured = new QStashVerifier("", null, clock);
        assertThatThrownBy(() -> unconfigured.verify(QStashTokens.sign(CURRENT_KEY, URL, BODY, NOW),
                BODY.getBytes(StandardCharsets.UTF_8), URL)).hasMessage("no signing keys configured");
    }
}
