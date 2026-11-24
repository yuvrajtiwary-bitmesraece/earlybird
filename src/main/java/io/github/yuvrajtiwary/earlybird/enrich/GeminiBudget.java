package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.persistence.GeminiUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Our own daily cap on Gemini calls ({@code GEMINI_MAX_CALLS_PER_DAY}, default 400), set below the free
 * tier's 500 requests/day so a burst can't lock us out of Google's quota. Resume parsing and job tagging
 * share it. Days are counted in Pacific time, because that's when Google resets the quota (midnight PT =
 * 13:30 IST); a UTC day would let us think we had calls left while Google was already refusing them.
 */
public class GeminiBudget {

    static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    private final GeminiUsageRepository usage;
    private final Clock clock;
    private final int maxPerDay;

    public GeminiBudget(GeminiUsageRepository usage, Clock clock, int maxPerDay) {
        this.usage = usage;
        this.clock = clock;
        this.maxPerDay = maxPerDay;
    }

    static LocalDate quotaDay(Instant instant) {
        return instant.atZone(QUOTA_ZONE).toLocalDate();
    }

    /** Takes one call from today's budget; false if it's used up. */
    public boolean tryConsume() {
        return usage.tryIncrement(quotaDay(clock.instant()), maxPerDay).isPresent();
    }

    public int remainingToday() {
        return Math.max(0, maxPerDay - usage.used(quotaDay(clock.instant())));
    }

    public int maxPerDay() {
        return maxPerDay;
    }
}
