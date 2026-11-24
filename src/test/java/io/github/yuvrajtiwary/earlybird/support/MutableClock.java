package io.github.yuvrajtiwary.earlybird.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move by hand, so time-based code is tested without sleeping. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** A view in another zone that follows this clock: advancing this clock moves the view too. */
    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock source = this;
        return new Clock() {
            @Override
            public Instant instant() {
                return source.instant();
            }

            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return source.withZone(other);
            }
        };
    }
}
