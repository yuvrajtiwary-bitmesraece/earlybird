package io.github.yuvrajtiwary.earlybird.matching;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yuvrajtiwary.earlybird.alerts.DigestService;
import io.github.yuvrajtiwary.earlybird.alerts.Notifier;
import io.github.yuvrajtiwary.earlybird.common.Base62;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import io.github.yuvrajtiwary.earlybird.support.PipelineFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole path, as in production but with fakes at the edges (spec section 9): a fake Greenhouse board gets a
 * new job → tick → tagging → matching → notifier → a fake Telegram receives the alert. Real Postgres in between,
 * and the in-process event bus; KafkaPipelineIT runs the same flow over Kafka.
 */
@IntegrationTest
class PipelineIT extends PipelineFixtures {

    @Autowired Matcher matcher;
    @Autowired DigestService digest;
    @Autowired Notifier notifier;

    @Test
    void aNewJobReachesTheRightUsersOnceAndTheRestGoToTheDigest() {
        User keen = activeUser(55);   // threshold 55: gets it instantly
        User picky = activeUser(95);  // threshold 95: gets it in the 20:00 digest
        Board board = boardWithBaseline();
        UUID postingId = newJob(board, "101", "Backend Engineer (Java)");

        tagging.drain();

        Alert instant = awaitAlert(keen, postingId, Alert.Status.SENT);
        assertThat(instant.mode()).isEqualTo(Alert.Mode.INSTANT);
        assertThat(instant.score()).isEqualTo(84); // 3 of 4 skills, fresher-friendly, exact city
        assertThat(instant.matchedSkills()).containsExactly("Java", "Spring Boot", "PostgreSQL");
        assertThat(instant.missingSkills()).containsExactly("Kafka");
        assertThat(instant.sentAt()).isNotNull();
        assertThat(messagesTo(keen)).singleElement().satisfies(m -> assertThat(m)
                .contains("New match · 84/100", "Backend Engineer (Java)", "fb:r:" + Base62.encode(instant.id())));

        Alert later = awaitAlert(picky, postingId, Alert.Status.QUEUED);
        assertThat(later.mode()).isEqualTo(Alert.Mode.DIGEST);

        // Running the matcher again for the same posting creates nothing and sends nothing.
        assertThat(matcher.createAlerts(postingId)).isEmpty();
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(5)).until(() -> messagesTo(keen).size() == 1);
        assertThat(jdbc.sql("select count(*) from alerts where posting_id = :p and user_id = :u")
                .param("p", postingId).param("u", keen.id()).query(Integer.class).single()).isEqualTo(1);

        // 20:00: the picky user gets one digest message.
        digest.sendAll();
        assertThat(alert(picky, postingId).status()).isEqualTo(Alert.Status.SENT);
        assertThat(messagesTo(picky)).singleElement().satisfies(m -> assertThat(m)
                .contains("Your EarlyBird digest", "Backend Engineer (Java)"));
    }

    @Test
    void aClosedJobCancelsAlertsNotYetSent() {
        User picky = activeUser(95);
        Board board = boardWithBaseline();
        UUID postingId = newJob(board, "201", "Backend Developer (Java)");
        tagging.drain();
        awaitAlert(picky, postingId, Alert.Status.QUEUED);

        // The job disappears from the board on two checks in a row: closed, and its queued alert cancelled.
        stubList(board, "W/\"gone\"");
        checker.check(reload(board));
        checker.check(reload(board));

        awaitAlert(picky, postingId, Alert.Status.CANCELLED);
    }

    @Test
    void feedbackButtonsAreStoredOnTheAlert() throws Exception {
        User keen = activeUser(55);
        Board board = boardWithBaseline();
        UUID postingId = newJob(board, "301", "Backend Engineer (Java)");
        tagging.drain();
        Alert sent = awaitAlert(keen, postingId, Alert.Status.SENT);

        tap(keen, "fb:r:" + Base62.encode(sent.id()));

        await().untilAsserted(() -> assertThat(alert(keen, postingId).feedback()).isEqualTo(Alert.Feedback.RELEVANT));
    }

    /**
     * Issue #3: another test class left more untagged, matching jobs than a user's daily cap (10). Once closed by
     * the fixture, tagging them later must not use up this test's user: its own job still arrives instantly.
     */
    @Test
    void untaggedJobsLeftByOtherTestsDoNotReachThisTestsUsers() throws Exception {
        Board other = boardWithBaseline();
        List<String> leftover = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String id = String.valueOf(5000 + i);
            leftover.add("{\"id\": " + id + ", \"title\": \"Java Developer " + i + "\", \"absolute_url\": "
                    + "\"https://jobs.example.com/" + id + "\", \"location\": {\"name\": \"Bengaluru, India\"}, "
                    + "\"first_published\": \"" + Instant.now() + "\", "
                    + "\"content\": \"Java and Spring Boot on PostgreSQL. Freshers welcome.\", \"departments\": []}");
        }
        String jobs = "{\"jobs\": [" + String.join(",", leftover) + "], \"meta\": {\"total\": 11}}";
        stubList(other, "W/\"leftover\"", leftover.toArray(String[]::new));
        // More new jobs than GreenhouseSource.DETAIL_CALLS_LIMIT: descriptions come from one ?content=true list call.
        fake.stubFor(get(urlPathEqualTo(path(other))).withQueryParam("content", equalTo("true")).willReturn(okJson(jobs)));
        checker.check(reload(other));
        assertThat(postings.findByBoard(other.id()))
                .as("leftovers that tagging would pick up: described and open")
                .hasSize(11).allSatisfy(p -> assertThat(p.descriptionText()).isNotBlank());
        closeOtherTestsUntaggedPostings(); // what the fixture does before each test
        assertThat(postings.findByBoard(other.id())).allSatisfy(p -> assertThat(p.closedAt()).isNotNull());

        User keen = activeUser(55);
        Board board = boardWithBaseline();
        UUID postingId = newJob(board, "501", "Backend Engineer (Java)");
        assertThat(tagging.drain()).as("the leftovers are tagged too, just closed").isGreaterThanOrEqualTo(12);

        Alert sent = awaitAlert(keen, postingId, Alert.Status.SENT);
        assertThat(sent.mode()).isEqualTo(Alert.Mode.INSTANT);
        assertThat(messagesTo(keen)).singleElement();
    }

    /**
     * At-least-once delivery means the same AlertRequested can arrive more than once, even at the same moment on
     * different consumers. The alert must still reach the user exactly once and count once against the daily cap.
     */
    @Test
    void anAlertDeliveredManyTimesAtOnceIsSentOnce() throws Exception {
        User user = activeUser(95);   // routed to the digest, so nothing has been sent yet
        Board board = boardWithBaseline();
        UUID postingId = newJob(board, "401", "Backend Engineer (Java)");
        tagging.drain();
        Alert queued = awaitAlert(user, postingId, Alert.Status.QUEUED);
        jdbc.sql("update alerts set mode = 'instant' where id = :id").param("id", queued.id()).update();

        int copies = 8;
        List<Alert.Status> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(copies)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Alert.Status>> futures = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return notifier.deliver(queued.id(), false);
                }));
            }
            start.countDown();
            for (Future<Alert.Status> f : futures) {
                outcomes.add(f.get(20, TimeUnit.SECONDS));
            }
        }

        // Copies that lost the claim say SENDING, or SENT if they looked after the winner finished.
        assertThat(outcomes).contains(Alert.Status.SENT).containsOnly(Alert.Status.SENT, Alert.Status.SENDING);
        assertThat(alert(user, postingId).status()).isEqualTo(Alert.Status.SENT);
        assertThat(messagesTo(user)).hasSize(1);   // the guarantee: one message, however many copies
        // A late copy, after the send, changes nothing either.
        assertThat(notifier.deliver(queued.id(), false)).isEqualTo(Alert.Status.SENT);
        assertThat(messagesTo(user)).hasSize(1);
    }
}
