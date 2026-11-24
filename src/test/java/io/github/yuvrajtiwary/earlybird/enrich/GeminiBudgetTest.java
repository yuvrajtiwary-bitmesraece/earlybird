package io.github.yuvrajtiwary.earlybird.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GeminiBudgetTest {

    @Test
    void theQuotaDayFollowsPacificTimeLikeGoogle() {
        // Nov 24 is in PST (UTC-8): Google's day turns over at 08:00 UTC, which is 13:30 IST.
        assertThat(GeminiBudget.quotaDay(Instant.parse("2026-11-24T07:59:59Z"))).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(GeminiBudget.quotaDay(Instant.parse("2026-11-24T08:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 24));
        // July is PDT (UTC-7): the turnover moves to 07:00 UTC.
        assertThat(GeminiBudget.quotaDay(Instant.parse("2026-07-10T06:59:59Z"))).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(GeminiBudget.quotaDay(Instant.parse("2026-07-10T07:00:00Z"))).isEqualTo(LocalDate.of(2026, 7, 10));
    }
}
