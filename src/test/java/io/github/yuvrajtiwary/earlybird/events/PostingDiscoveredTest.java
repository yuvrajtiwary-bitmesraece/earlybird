package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostingDiscoveredTest {

    private static Posting posting(String description) {
        return new Posting(Ids.newId(), 1, Source.LEVER, "abc", "https://jobs.lever.co/acme/abc", "SDE 1", "Acme",
                "Pune, IN", List.of("Engineering"), null, Instant.parse("2026-11-24T08:30:00Z"),
                Instant.parse("2026-11-24T08:30:00Z"), 0, null, "hash", description, false, null, true);
    }

    @Test
    void carriesThePostingAndItsBoard() {
        Posting p = posting("Java and Kafka");

        PostingDiscovered d = PostingDiscovered.of(p, "acme");

        assertThat(d.postingId()).isEqualTo(p.id());
        assertThat(d.source()).isEqualTo("lever");
        assertThat(d.boardToken()).isEqualTo("acme");
        assertThat(d.description()).isEqualTo("Java and Kafka");
        assertThat(d.baseline()).isTrue();
    }

    /** 3,000 characters of Devanagari or CJK is up to 9 KB of UTF-8; the event must still stay under 8 KB. */
    @Test
    void nonLatinDescriptionsAreCutToFitUnder8Kb() {
        String hindi = "सॉफ्टवेयर इंजीनियर ".repeat(160); // ~3,000 characters, ~8,600 bytes

        PostingDiscovered d = PostingDiscovered.of(posting(hindi), "acme");

        assertThat(d.description().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(6_000);
        assertThat(hindi).startsWith(d.description()); // cut, never garbled: no half characters
        byte[] wire = new EventJson().write(EventEnvelope.of(d, "poller", Clock.systemUTC()));
        assertThat(wire).hasSizeLessThan(KafkaEventBus.MAX_EVENT_BYTES);
    }

    @Test
    void shortTextIsLeftAlone() {
        assertThat(PostingDiscovered.fitting("héllo", 6)).isEqualTo("héllo");
        assertThat(PostingDiscovered.fitting("héllo", 2)).isEqualTo("h"); // "é" is 2 bytes and doesn't fit
        assertThat(PostingDiscovered.fitting(null, 10)).isNull();
    }
}
