package io.github.yuvrajtiwary.earlybird.sources;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceJobTest {

    @Test
    void formatsLocations() {
        assertThat(SourceJob.formatLocation(List.of("Bengaluru", "Pune"), "IN", "hybrid"))
                .isEqualTo("Bengaluru / Pune, IN · Hybrid");
        assertThat(SourceJob.formatLocation(List.of("Remote (EMEA)"), null, "Remote")).isEqualTo("Remote (EMEA)");
        assertThat(SourceJob.formatLocation(List.of("India"), "IN", "remote")).isEqualTo("India, IN · Remote");
        assertThat(SourceJob.formatLocation(List.of(), null, "OnSite")).isEqualTo("On-site");
        assertThat(SourceJob.formatLocation(Arrays.asList(null, " "), null, "unspecified")).isNull();
    }

    @Test
    void normalizesFields() {
        var job = new SourceJob("1", "  Senior   Engineer ", "https://x", "  ", Arrays.asList("Eng", null, " ", "Eng"),
                null, "word ".repeat(1000));

        assertThat(job.title()).isEqualTo("Senior Engineer");
        assertThat(job.locationRaw()).isNull();
        assertThat(job.departments()).containsExactly("Eng");
        assertThat(job.description().length()).isLessThanOrEqualTo(SourceJob.MAX_DESCRIPTION);
    }
}
