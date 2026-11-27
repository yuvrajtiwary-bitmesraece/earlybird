package io.github.yuvrajtiwary.earlybird.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTagsRepository;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.support.KafkaIntegrationTest;
import io.github.yuvrajtiwary.earlybird.support.PipelineFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Block 5 against a real Kafka (spec section 9: "Kafka round trip, including the DLQ"): the whole pipeline over
 * Kafka, a poison message, a failing consumer's retries, a redelivered event, the outbox relay, and an outage.
 */
@KafkaIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaPipelineIT extends PipelineFixtures {

    private static final String ENRICHED = "eb.jobs.enriched.v1";
    private static final String DLQ = "eb.dlq.v1";

    @Autowired EventBus bus;
    @Autowired KafkaMonitor monitor;
    @Autowired OutboxRelay relay;
    @Autowired PostingTagsRepository tags;
    @Autowired KafkaContainer kafka;

    private final EventJson json = new EventJson();

    /** "With EVENT_BUS=kafka the same flow works": new job → tick → tagging → matching → notifier, via Kafka. */
    @Test
    void theWholeFlowRunsOverKafka() {
        assertThat(bus).isInstanceOf(KafkaEventBus.class);
        User keen = activeUser(55);
        Board board = boardWithBaseline();

        UUID postingId = newJob(board, "101", "Backend Engineer (Java)");
        assertThat(posting(postingId).eventPublishedAt()).as("Kafka acknowledged PostingDiscovered").isNotNull();
        tagging.drain();

        Alert sent = awaitAlert(keen, postingId, Alert.Status.SENT);
        assertThat(sent.score()).isEqualTo(84);
        assertThat(messagesTo(keen)).singleElement()
                .satisfies(m -> assertThat(m).contains("Strong match", "84/100", "Backend Engineer (Java)"));
        assertThat(enrichedPublished(postingId)).as("Kafka acknowledged PostingEnriched").isTrue();
    }

    /** "A CI test sends a poison message to the DLQ": straight there, without retries, and the admin is told. */
    @Test
    void aPoisonMessageIsDeadLetteredAtOnceAndTheAdminIsTold() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var producer = rawProducer(); var deadLetters = deadLetterReader()) {
            producer.send(new ProducerRecord<>(ENRICHED, key, "not json".getBytes(StandardCharsets.UTF_8)))
                    .get(15, TimeUnit.SECONDS);

            EventEnvelope<DeadLetter> letter = awaitDeadLetter(deadLetters, key);

            assertThat(letter.producer()).isEqualTo("eb-matcher");
            assertThat(letter.payload().originalTopic()).isEqualTo(ENRICHED);
            assertThat(letter.payload().key()).isEqualTo(key);
            assertThat(letter.payload().attempts()).as("poison is never retried").isEqualTo(1);
            assertThat(letter.payload().error()).startsWith("DeadLetterException: not JSON");
            assertThat(new String(Base64.getDecoder().decode(letter.payload().payload()), StandardCharsets.UTF_8))
                    .isEqualTo("not json");
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(messagesToChat(KafkaIntegrationTest.ADMIN_CHAT))
                .anySatisfy(m -> assertThat(m).contains("Dead letter", ENRICHED)));
        assertThat(monitor.deadLetters().orElse(0)).as("the count /admin shows").isPositive();

        // The partition isn't stuck behind the poison: the next event on it is handled.
        var next = EventEnvelope.of(enrichedFor(UUID.randomUUID()), "it", Clock.systemUTC());
        bus.publish(Topic.JOBS_ENRICHED, key, next).get(15, TimeUnit.SECONDS);
        awaitProcessed(next.eventId());
    }

    /** Spec 3.2: retried after 1 s, 5 s and 30 s (100 ms each here), then dead-lettered with the attempt count. */
    @Test
    void aFailingEventIsTriedFourTimesThenDeadLettered() throws Exception {
        UUID target = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        bus.subscribe(Topic.JOBS_CLOSED, "it-flaky", PostingClosed.class, event -> {
            if (event.payload().postingId().equals(target)) {
                attempts.incrementAndGet();
                throw new IllegalStateException("database is down");
            }
        });

        try (var deadLetters = deadLetterReader()) {
            bus.publish(Topic.JOBS_CLOSED, target.toString(),
                    EventEnvelope.of(new PostingClosed(target, Instant.now()), "it", Clock.systemUTC()))
                    .get(15, TimeUnit.SECONDS);

            EventEnvelope<DeadLetter> letter = awaitDeadLetter(deadLetters, target.toString());

            assertThat(letter.producer()).isEqualTo("it-flaky");
            assertThat(letter.payload().attempts()).isEqualTo(4);
            assertThat(letter.payload().error()).isEqualTo("IllegalStateException: database is down");
        }
        assertThat(attempts).hasValue(4);
    }

    /** At-least-once delivery: a second copy of an event is skipped by processed_events, not handled again. */
    @Test
    void aRedeliveredEventIsSkipped() throws Exception {
        User picky = activeUser(95); // routed to the digest, so nothing is sent and only rows matter
        Board board = boardWithBaseline();
        UUID postingId = newJob(board, "201", "Backend Engineer (Java)");
        tagging.drain();
        awaitAlert(picky, postingId, Alert.Status.QUEUED);

        var event = EventEnvelope.of(PostingEnriched.of(tags.findByPostingId(postingId).orElseThrow()), "it",
                Clock.systemUTC());
        bus.publish(Topic.JOBS_ENRICHED, postingId.toString(), event).get(15, TimeUnit.SECONDS);
        awaitProcessed(event.eventId());
        // Without its alert, only processed_events can stop a copy of the event from creating it again.
        jdbc.sql("delete from alerts where posting_id = :p").param("p", postingId).update();

        bus.publish(Topic.JOBS_ENRICHED, postingId.toString(), event).get(15, TimeUnit.SECONDS);
        // A marker behind the copy on the same partition: once it's handled, so is the copy (in-order delivery).
        var marker = EventEnvelope.of(enrichedFor(UUID.randomUUID()), "it", Clock.systemUTC());
        bus.publish(Topic.JOBS_ENRICHED, postingId.toString(), marker).get(15, TimeUnit.SECONDS);
        awaitProcessed(marker.eventId());

        assertThat(alert(picky, postingId)).as("the copy was skipped, so no alert was created again").isNull();
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    /** Outbox-lite: rows whose event never reached Kafka (a crash between write and publish) go out later. */
    @Test
    void theRelayPublishesWhatNeverReachedKafka() throws Exception {
        User picky = activeUser(95);
        Board board = boardWithBaseline();
        UUID postingId = newJob(board, "301", "Backend Engineer (Java)");
        Instant minutesAgo = Instant.now().minus(Duration.ofMinutes(3));

        // PostingDiscovered "lost": unpublished, and older than the 1-minute grace period.
        jdbc.sql("update postings set event_published_at = null, first_seen_at = :t where id = :id")
                .param("t", utc(minutesAgo)).param("id", postingId).update();
        assertThat(relay.run().discovered()).isGreaterThanOrEqualTo(1);
        assertThat(posting(postingId).eventPublishedAt()).isNotNull();

        // PostingEnriched "lost".
        tagging.drain();
        Alert queued = awaitAlert(picky, postingId, Alert.Status.QUEUED);
        jdbc.sql("update posting_tags set event_published_at = null, enriched_at = :t where posting_id = :id")
                .param("t", utc(minutesAgo)).param("id", postingId).update();
        assertThat(relay.run().enriched()).isGreaterThanOrEqualTo(1);
        assertThat(enrichedPublished(postingId)).isTrue();

        // AlertRequested "lost": an instant alert still queued after 2 minutes is requested again, and sent.
        jdbc.sql("update alerts set mode = 'instant', created_at = :t where id = :id")
                .param("t", utc(minutesAgo)).param("id", queued.id()).update();
        assertThat(relay.run().alerts()).isGreaterThanOrEqualTo(1);
        awaitAlert(picky, postingId, Alert.Status.SENT);
        assertThat(messagesTo(picky)).hasSize(1);
    }

    /**
     * Spec section 8: with Kafka unreachable, health is DEGRADED (HTTP 200, readiness UP, so the platform never
     * restarts the app), new postings are saved unpublished without waiting on timeouts, and the relay publishes
     * them once Kafka is back. Runs last: it freezes the broker for a few seconds.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void whileKafkaIsDownTheAppIsDegradedAndCatchesUpAfterwards() throws Exception {
        monitor.probe();
        assertThat(get("/actuator/health").body()).contains("\"status\":\"UP\"");
        Board board = boardWithBaseline();

        var docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        UUID postingId;
        try {
            monitor.probe(); // times out after 10 s
            assertThat(monitor.unreachable()).isTrue();
            HttpResponse<String> health = get("/actuator/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"DEGRADED\"");
            assertThat(get("/actuator/health/readiness").body()).contains("\"status\":\"UP\"");

            long started = System.nanoTime();
            postingId = newJob(board, "401", "Backend Engineer (Java)");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("publishing failed fast")
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(posting(postingId).eventPublishedAt()).as("saved, not announced").isNull();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> {
            monitor.probe();
            return !monitor.unreachable();
        });
        assertThat(get("/actuator/health").body()).contains("\"status\":\"UP\"");
        jdbc.sql("update postings set first_seen_at = :t where id = :id")
                .param("t", utc(Instant.now().minus(Duration.ofMinutes(2)))).param("id", postingId)
                .update();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            relay.run();
            assertThat(posting(postingId).eventPublishedAt()).isNotNull();
        });
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static PostingEnriched enrichedFor(UUID postingId) {
        return new PostingEnriched(postingId, "backend", "junior", 0.0, 2.0, true, "full_time", "onsite", "unknown",
                List.of(), List.of("IN"), List.of("Java"), "fallback");
    }

    private boolean enrichedPublished(UUID postingId) {
        return jdbc.sql("select event_published_at is not null from posting_tags where posting_id = :p")
                .param("p", postingId).query(Boolean.class).single();
    }

    private int processedCount(UUID eventId) {
        return jdbc.sql("select count(*) from processed_events where event_id = :e and consumer = 'eb-matcher'")
                .param("e", eventId).query(Integer.class).single();
    }

    private void awaitProcessed(UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> processedCount(eventId) == 1);
    }

    private KafkaProducer<String, byte[]> rawProducer() {
        return new KafkaProducer<>(Map.of("bootstrap.servers", kafka.getBootstrapServers()), new StringSerializer(),
                new ByteArraySerializer());
    }

    /** Reads the dead-letter topic from the start, in a group of its own. */
    private KafkaConsumer<String, byte[]> deadLetterReader() {
        var consumer = new KafkaConsumer<>(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", "it-reader-" + UUID.randomUUID(), "auto.offset.reset", "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(DLQ));
        return consumer;
    }

    private EventEnvelope<DeadLetter> awaitDeadLetter(KafkaConsumer<String, byte[]> consumer, String key) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return json.read(record.value(), DeadLetter.class);
                }
            }
        }
        throw new AssertionError("no dead letter with key " + key + " within 60 s");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
