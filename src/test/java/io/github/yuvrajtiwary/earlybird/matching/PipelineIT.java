package io.github.yuvrajtiwary.earlybird.matching;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.yuvrajtiwary.earlybird.alerts.DigestService;
import io.github.yuvrajtiwary.earlybird.common.Base62;
import io.github.yuvrajtiwary.earlybird.enrich.TaggingWorker;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.ProfileRepository;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.poller.BoardChecker;
import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The whole path, as in production but with fakes at the edges (spec section 9): a fake Greenhouse board gets a
 * new job → tick → tagging → matching → notifier → a fake Telegram receives the alert. Real Postgres in between.
 * Gemini is made to refuse tagging, so the deterministic keyword tagger runs and the test is repeatable.
 */
@IntegrationTest
class PipelineIT {

    private static final AtomicLong IDS = new AtomicLong(9_000_000);
    private static final String SECRET = "it-webhook-secret-0123456789abcdefghijklmnop";

    @Autowired BoardChecker checker;
    @Autowired BoardRepository boards;
    @Autowired PostingRepository postings;
    @Autowired TaggingWorker tagging;
    @Autowired Matcher matcher;
    @Autowired DigestService digest;
    @Autowired AlertRepository alerts;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired JdbcClient jdbc;
    @Autowired WireMockServer fake;
    @Value("${local.server.port}") int port;

    @BeforeEach
    void stubTelegramAndGemini() {
        fake.stubFor(post(urlPathMatching("/bot[^/]+/sendMessage")).willReturn(okJson(
                "{\"ok\": true, \"result\": {\"message_id\": 900, \"date\": 1, \"chat\": {\"id\": 1, \"type\": \"private\"}}}")));
        fake.stubFor(post(urlPathMatching("/bot[^/]+/(answerCallbackQuery|editMessageText|editMessageReplyMarkup)"))
                .willReturn(okJson("{\"ok\": true, \"result\": true}")));
        // Tagging requests are refused (400, not retried), so the keyword fallback tags deterministically.
        fake.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .withRequestBody(containing("You tag job postings"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\": {\"status\": \"INVALID_ARGUMENT\"}}")));
    }

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

    // ------------------------------------------------------------------------------------------ helpers

    private User activeUser(int minScore) {
        long tg = IDS.incrementAndGet();
        User user = users.create(tg, tg, "Pipeline", null, Instant.now());
        users.activate(user.id(), Instant.now());
        profiles.upsert(new Profile(user.id(), List.of(RoleCategory.BACKEND), List.of("Bengaluru"), false, false, 0,
                false, List.of("Java", "Spring Boot", "PostgreSQL"), List.of(), 2025, minScore, 10, null, null, Instant.now()));
        return users.findById(user.id()).orElseThrow();
    }

    /** A board whose first (baseline) check found nothing, so the next job is genuinely new. */
    private Board boardWithBaseline() {
        String token = "pipe-" + UUID.randomUUID().toString().substring(0, 8);
        boards.insertIfAbsent(Source.GREENHOUSE, token, "Pipe " + token);
        Board board = boards.find(Source.GREENHOUSE, token).orElseThrow();
        fake.stubFor(get(urlPathEqualTo(path(board))).withQueryParam("content", equalTo("true"))
                .willReturn(okJson("{\"jobs\": [], \"meta\": {\"total\": 0}}")));
        checker.check(board);
        return reload(board);
    }

    private UUID newJob(Board board, String id, String title) {
        stubList(board, "W/\"" + id + "\"", "{\"id\": " + id + ", \"title\": \"" + title
                + "\", \"absolute_url\": \"https://jobs.example.com/" + id + "\", \"location\": {\"name\": \"Bengaluru, India\"}, "
                + "\"first_published\": \"" + Instant.now() + "\"}");
        String content = "&lt;p&gt;Build APIs in Java and Spring Boot on PostgreSQL, with Kafka. 0-2 years of "
                + "experience. Freshers welcome.&lt;/p&gt;";
        fake.stubFor(get(urlPathEqualTo(path(board) + "/" + id)).willReturn(okJson(
                "{\"id\": " + id + ", \"content\": \"" + content + "\", \"departments\": []}")));
        checker.check(reload(board));
        return postings.findByBoard(board.id()).stream().filter(p -> p.externalId().equals(id)).findFirst().orElseThrow().id();
    }

    private void stubList(Board board, String etag, String... jobs) {
        fake.stubFor(get(urlPathEqualTo(path(board))).withQueryParam("content", absent())
                .willReturn(okJson("{\"jobs\": [" + String.join(",", jobs) + "], \"meta\": {\"total\": " + jobs.length + "}}")
                        .withHeader("ETag", etag)));
    }

    private static String path(Board board) {
        return "/v1/boards/" + board.token() + "/jobs";
    }

    private Board reload(Board board) {
        return boards.findById(board.id()).orElseThrow();
    }

    /** The alert for this user and posting, or null if there is none (yet). */
    private Alert alert(User user, UUID postingId) {
        return jdbc.sql("select id from alerts where user_id = :u and posting_id = :p")
                .param("u", user.id()).param("p", postingId).query(UUID.class).optional()
                .flatMap(alerts::findById)
                .orElse(null);
    }

    private Alert awaitAlert(User user, UUID postingId, Alert.Status status) {
        return await().atMost(Duration.ofSeconds(20))
                .until(() -> alert(user, postingId), a -> a != null && a.status() == status);
    }

    private List<String> messagesTo(User user) {
        return fake.findAll(postRequestedFor(urlPathMatching("/bot[^/]+/sendMessage"))
                        .withRequestBody(matchingJsonPath("$.chat_id", equalTo(String.valueOf(user.chatId())))))
                .stream().map(LoggedRequest::getBodyAsString).toList();
    }

    private void tap(User user, String data) throws Exception {
        String update = "{\"update_id\": " + IDS.incrementAndGet() + ", \"callback_query\": {\"id\": \"cb" + IDS.incrementAndGet()
                + "\", \"data\": \"" + data + "\", \"from\": {\"id\": " + user.telegramUserId() + ", \"is_bot\": false, "
                + "\"first_name\": \"P\"}, \"message\": {\"message_id\": 900, \"date\": 1, \"chat\": {\"id\": "
                + user.chatId() + ", \"type\": \"private\"}}}}";
        try (HttpClient http = HttpClient.newHttpClient()) {
            int status = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/telegram/webhook"))
                    .header("Content-Type", "application/json").header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                    .POST(HttpRequest.BodyPublishers.ofString(update)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            assertThat(status).isEqualTo(200);
        }
    }
}
