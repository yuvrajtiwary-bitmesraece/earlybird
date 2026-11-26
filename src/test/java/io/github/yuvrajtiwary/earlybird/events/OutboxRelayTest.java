package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository.Unpublished;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTagsRepository;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-24T08:30:00Z");

    /** Records what was published; keys in {@code refuse} fail, as if Kafka were down. */
    static final class RecordingBus implements EventBus {
        record Published(Topic topic, String key, EventEnvelope<?> event) {}

        final List<Published> published = new ArrayList<>();
        final Set<String> refuse = new HashSet<>();

        @Override
        public CompletableFuture<Void> publish(Topic topic, String key, EventEnvelope<?> event) {
            if (refuse.contains(key)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Kafka is unreachable"));
            }
            published.add(new Published(topic, key, event));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> void subscribe(Topic topic, String group, Class<T> type, EventHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
    }

    private final RecordingBus bus = new RecordingBus();
    private final PostingRepository postings = mock(PostingRepository.class);
    private final PostingTagsRepository tags = mock(PostingTagsRepository.class);
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final OutboxRelay relay = new OutboxRelay(bus, postings, tags, alerts, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Posting posting() {
        return new Posting(Ids.newId(), 1, Source.GREENHOUSE, "1", "https://x", "Backend Engineer", "Acme", "Pune",
                List.of(), null, NOW.minusSeconds(120), NOW, 0, null, "h", "Java, Kafka", false, null, false);
    }

    private static PostingTags tagsFor(UUID postingId) {
        return new PostingTags(postingId, RoleCategory.BACKEND, PostingTags.Seniority.JUNIOR, 0.0, 2.0, true,
                PostingTags.EmploymentType.FULL_TIME, PostingTags.WorkMode.ONSITE, PostingTags.RemoteScope.UNKNOWN,
                List.of("Pune"), List.of("IN"), List.of("Java"), null, PostingTags.Tagger.FALLBACK, null,
                NOW.minusSeconds(90));
    }

    private static Alert queuedInstant(long userId) {
        return new Alert(Ids.newId(), userId, Ids.newId(), 80, List.of("Java"), List.of(), Alert.Mode.INSTANT,
                Alert.Status.QUEUED, NOW.minusSeconds(180), null, null, null, null);
    }

    @Test
    void republishesAllThreeKindsAndMarksWhatTheBusAccepted() {
        Posting p = posting();
        PostingTags t = tagsFor(Ids.newId());
        Alert a = queuedInstant(42);
        when(postings.findUnpublished(any(), any(), anyInt())).thenReturn(List.of(new Unpublished(p, "acme")));
        when(tags.findUnpublished(any(), any(), anyInt())).thenReturn(List.of(t));
        when(alerts.findQueuedInstant(any(), any(), anyInt())).thenReturn(List.of(a));

        OutboxRelay.Result result = relay.run();

        assertThat(result).isEqualTo(new OutboxRelay.Result(1, 1, 1, false));
        assertThat(bus.published).extracting(RecordingBus.Published::topic)
                .containsExactly(Topic.JOBS_DISCOVERED, Topic.JOBS_ENRICHED, Topic.ALERTS_REQUESTED);
        // Keys as the producers use them: posting id, posting id, user id (one user's alerts stay in order).
        assertThat(bus.published).extracting(RecordingBus.Published::key)
                .containsExactly(p.id().toString(), t.postingId().toString(), "42");
        assertThat(bus.published.get(0).event().payload()).isEqualTo(PostingDiscovered.of(p, "acme"));
        assertThat(bus.published.get(1).event().payload()).isEqualTo(PostingEnriched.of(t));
        assertThat(bus.published.get(2).event().payload()).isEqualTo(AlertRequested.of(a, false));
        verify(postings).markPublished(List.of(p.id()), NOW);
        verify(tags).markPublished(List.of(t.postingId()), NOW);
    }

    /** Only rows past their grace period: younger ones are still being published by their producer. */
    @Test
    void looksOnlyAtRowsPastTheirGracePeriodAndNotTooOld() {
        relay.run();

        verify(postings).findUnpublished(NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofDays(7)),
                OutboxRelay.BATCH);
        verify(tags).findUnpublished(NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofDays(7)),
                OutboxRelay.BATCH);
        verify(alerts).findQueuedInstant(NOW.minus(Duration.ofMinutes(2)), NOW.minus(Duration.ofHours(24)),
                OutboxRelay.BATCH);
    }

    /** Kafka is down: mark only what got through, and don't wait on timeouts for the remaining kinds. */
    @Test
    void stopsAtTheFirstFailure() {
        Posting ok = posting();
        Posting refused = posting();
        bus.refuse.add(refused.id().toString());
        when(postings.findUnpublished(any(), any(), anyInt()))
                .thenReturn(List.of(new Unpublished(ok, "acme"), new Unpublished(refused, "acme")));

        OutboxRelay.Result result = relay.run();

        assertThat(result).isEqualTo(new OutboxRelay.Result(1, 0, 0, true));
        verify(postings).markPublished(List.of(ok.id()), NOW);
        verify(tags, never()).findUnpublished(any(), any(), anyInt());
        verify(alerts, never()).findQueuedInstant(any(), any(), anyInt());
    }

    @Test
    void nothingToDoPublishesNothing() {
        assertThat(relay.run()).isEqualTo(new OutboxRelay.Result(0, 0, 0, false));
        assertThat(bus.published).isEmpty();
        verify(postings).markPublished(eq(List.of()), any());
    }
}
