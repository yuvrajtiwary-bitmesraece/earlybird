package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class RetryBackOffTest {

    @Test
    void waitsOneFiveThenThirtySecondsThenGivesUp() {
        var backOff = new RetryBackOff(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)));

        BackOffExecution attempts = backOff.start();

        assertThat(attempts.nextBackOff()).isEqualTo(1_000);
        assertThat(attempts.nextBackOff()).isEqualTo(5_000);
        assertThat(attempts.nextBackOff()).isEqualTo(30_000);
        assertThat(attempts.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    /** Each failing event gets the full sequence: one event's retries don't use up another's. */
    @Test
    void everyFailureStartsAFreshSequence() {
        var backOff = new RetryBackOff(List.of(Duration.ofSeconds(1)));

        BackOffExecution first = backOff.start();
        first.nextBackOff();

        assertThat(first.nextBackOff()).isEqualTo(BackOffExecution.STOP);
        assertThat(backOff.start().nextBackOff()).isEqualTo(1_000);
    }

    @Test
    void noWaitsMeansNoRetries() {
        assertThat(new RetryBackOff(List.of()).start().nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
