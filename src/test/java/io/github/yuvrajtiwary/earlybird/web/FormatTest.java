package io.github.yuvrajtiwary.earlybird.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormatTest {

    private static final Instant NOW = Instant.parse("2026-11-27T08:30:00Z");

    @Test
    void timesAreShownInIndiaTime() {
        assertThat(Format.when(NOW)).isEqualTo("27 Nov, 14:00 IST");
        assertThat(Format.when(null)).isEqualTo("never");
    }

    @Test
    void foundAgo() {
        assertThat(Format.ago(NOW.minusSeconds(20), NOW)).isEqualTo("just now");
        assertThat(Format.ago(NOW.minus(Duration.ofMinutes(4)), NOW)).isEqualTo("4 min ago");
        assertThat(Format.ago(NOW.minus(Duration.ofHours(3)), NOW)).isEqualTo("3 h ago");
        assertThat(Format.ago(NOW.minus(Duration.ofDays(3)), NOW)).isEqualTo("3 days ago");
    }

    @Test
    void durations() {
        assertThat(Format.seconds(null)).isEqualTo("–");
        assertThat(Format.seconds(42.4)).isEqualTo("42 s");
        assertThat(Format.seconds(210.0)).isEqualTo("3.5 min");
        assertThat(Format.seconds(7560.0)).isEqualTo("2.1 h");
    }

    @Test
    void tagsReadLikeTheAlert() {
        assertThat(Format.tags("new_grad", "hybrid", 0.0, 2.0)).isEqualTo("New grad · Hybrid · 0–2 yrs");
        assertThat(Format.tags("unknown", "remote", 1.5, null)).isEqualTo("Remote · 1.5+ yrs");
        assertThat(Format.tags(null, null, null, null)).isEmpty();
    }

    /** ?role= is matched against the known roles only; anything else (or "other") shows everything. */
    @Test
    void onlyKnownRolesFilter() {
        assertThat(Format.role("Backend")).hasValue("backend");
        assertThat(Format.role("data_science_ml")).hasValue("data_science_ml");
        assertThat(Format.role("other")).isEmpty();
        assertThat(Format.role("'; drop table postings; --")).isEmpty();
        assertThat(Format.ROLES).hasSize(10).containsKeys("backend", "embedded");
    }

    /** 14 bars, one per day, scaled to the busiest day; empty days get no bar but keep their slot. */
    @Test
    void chartBars() {
        LocalDate first = LocalDate.of(2026, 11, 14);
        var chart = PublicData.chart(Map.of(first, 10, first.plusDays(13), 5), first);

        assertThat(chart.bars()).hasSize(14);
        assertThat(chart.max()).isEqualTo(10);
        assertThat(chart.total()).isEqualTo(15);
        assertThat(chart.bars().get(0).height()).isEqualTo(140);
        assertThat(chart.bars().get(13).height()).isEqualTo(70);
        assertThat(chart.bars().get(13).y()).isEqualTo(70);
        assertThat(chart.bars().get(5).height()).isZero();
        assertThat(chart.bars().get(0).day()).isEqualTo("14 Nov");
        assertThat(chart.bars().get(13).x() + chart.bars().get(13).width()).isLessThanOrEqualTo(600);
    }
}
