package io.github.yuvrajtiwary.earlybird.sources;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentHashTest {

    @Test
    void isAStableSha256Hex() {
        String hash = ContentHash.of("ExampleCo", "Backend Engineer", "Bengaluru", "Build APIs.");

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(ContentHash.of("ExampleCo", "Backend Engineer", "Bengaluru", "Build APIs.")).isEqualTo(hash);
    }

    @Test
    void ignoresCaseAndWhitespace() {
        assertThat(ContentHash.of("ExampleCo", "Backend  Engineer", "Bengaluru", "Build APIs."))
                .isEqualTo(ContentHash.of("exampleco", "backend engineer", "  BENGALURU ", "build\n  apis."));
    }

    @Test
    void changesWhenWhatThePostingSaysChanges() {
        String base = ContentHash.of("ExampleCo", "Backend Engineer", "Bengaluru", "Build APIs.");

        assertThat(ContentHash.of("ExampleCo", "Frontend Engineer", "Bengaluru", "Build APIs.")).isNotEqualTo(base);
        assertThat(ContentHash.of("ExampleCo", "Backend Engineer", "Pune", "Build APIs.")).isNotEqualTo(base);
        assertThat(ContentHash.of("OtherCo", "Backend Engineer", "Bengaluru", "Build APIs.")).isNotEqualTo(base);
    }

    @Test
    void onlyTheFirst1000CharactersOfTheDescriptionCount() {
        String start = "x".repeat(ContentHash.DESCRIPTION_PREFIX);

        assertThat(ContentHash.of("A", "B", "C", start + " footer v1"))
                .isEqualTo(ContentHash.of("A", "B", "C", start + " footer v2"));
    }

    @Test
    void handlesMissingParts() {
        assertThat(ContentHash.of("A", "B", null, null)).hasSize(64);
    }
}
