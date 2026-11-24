package io.github.yuvrajtiwary.earlybird.poller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.sources.SourceJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class BoardDifferTest {

    private static final Instant NOW = Instant.parse("2026-11-24T08:30:00Z");

    private static Board board(Instant lastSuccessAt, Integer lastJobCount, int consecutiveEmptyChecks) {
        return new Board(1, Source.GREENHOUSE, "acme", "Acme", true, 180, NOW, NOW, lastSuccessAt, 0, null,
                lastJobCount, NOW, consecutiveEmptyChecks, null);
    }

    private static SourceJob job(String id) {
        return new SourceJob(id, "Engineer " + id, "https://x/" + id, null, List.of(), null, null);
    }

    @Test
    void theFirstSuccessfulCheckIsTheBaseline() {
        assertThat(BoardDiffer.isBaselineCheck(board(null, null, 0))).isTrue();
        assertThat(BoardDiffer.isBaselineCheck(board(NOW, 3, 0))).isFalse();
    }

    @ParameterizedTest(name = "had {0} jobs, {1} empty answers so far, now {2} jobs -> suspicious={3}")
    @CsvSource(nullValues = "null", value = {
        "10, 0, 0, true",    // first sudden zero: ignore
        "10, 1, 0, true",    // second: ignore
        "10, 2, 0, false",   // third in a row: believe it
        "5,  0, 0, true",    // threshold is inclusive
        "4,  0, 0, false",   // small boards really do empty out
        "null, 0, 0, false", // never had a count
        "10, 0, 3, false",   // not empty at all
    })
    void suspiciousEmptyGuard(Integer hadJobs, int emptyChecks, int jobsNow, boolean suspicious) {
        assertThat(BoardDiffer.isSuspiciousEmpty(board(NOW, hadJobs, emptyChecks), jobsNow)).isEqualTo(suspicious);
    }

    @Test
    void newJobsAreThoseNeverSeenOnThisBoard() {
        assertThat(BoardDiffer.newJobs(List.of(job("1"), job("2"), job("3")), Set.of("2")))
                .extracting(SourceJob::externalId).containsExactly("1", "3");
    }

    @Test
    void dedupeKeepsTheFirstOfRepeatedIds() {
        var first = new SourceJob("1", "First", "https://x/1", null, List.of(), null, null);
        var again = new SourceJob("1", "Again", "https://x/1", null, List.of(), null, null);

        assertThat(BoardDiffer.dedupe(List.of(first, job("2"), again))).containsExactly(first, job("2"));
    }

    @Test
    void whatGetsPublishedForTagging() {
        Instant seen = NOW;
        Duration window = BoardDiffer.RECENT_BASELINE_WINDOW;

        assertThat(BoardDiffer.shouldPublish(false, false, null, seen)).as("new job").isTrue();
        assertThat(BoardDiffer.shouldPublish(false, true, null, seen)).as("repost").isFalse();
        assertThat(BoardDiffer.shouldPublish(true, false, null, seen)).as("baseline, no publish time").isFalse();
        assertThat(BoardDiffer.shouldPublish(true, false, seen.minus(Duration.ofHours(2)), seen))
                .as("baseline, published 2 h before").isTrue();
        assertThat(BoardDiffer.shouldPublish(true, false, seen.minus(window), seen))
                .as("baseline, exactly 48 h").isTrue();
        assertThat(BoardDiffer.shouldPublish(true, false, seen.minus(window).minusSeconds(1), seen))
                .as("baseline, just over 48 h").isFalse();
        assertThat(BoardDiffer.shouldPublish(true, true, seen.minus(Duration.ofHours(2)), seen))
                .as("baseline repost").isFalse();
    }
}
