package io.github.yuvrajtiwary.earlybird.events;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Waits a fixed list of intervals between attempts, then stops. The spec's (1 s, 5 s, 30 s) means 4 attempts in
 * all; neither a fixed nor an exponential back-off gives that sequence. A short first wait rides out a blip, the
 * last gives a restarting database or a Telegram 5xx half a minute.
 */
final class RetryBackOff implements BackOff {

    private final List<Duration> waits;

    RetryBackOff(List<Duration> waits) {
        this.waits = List.copyOf(waits);
    }

    @Override
    public BackOffExecution start() {
        Iterator<Duration> next = waits.iterator();
        return () -> next.hasNext() ? next.next().toMillis() : BackOffExecution.STOP;
    }

    @Override
    public String toString() {
        return "RetryBackOff" + waits;
    }
}
