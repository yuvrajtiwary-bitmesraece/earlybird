package io.github.yuvrajtiwary.earlybird.support;

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
import io.github.yuvrajtiwary.earlybird.enrich.TaggingWorker;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.ProfileRepository;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.poller.BoardChecker;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the end-to-end tests share: users, a fake Greenhouse board with a new job on it, and the messages a fake
 * Telegram received. Gemini refuses tagging (a 400, not retried), so the deterministic keyword tagger runs and
 * the tests are repeatable. Subclasses add {@code @IntegrationTest} or {@code @KafkaIntegrationTest}.
 */
public abstract class PipelineFixtures {

    protected static final AtomicLong IDS = new AtomicLong(9_000_000);
    protected static final String SECRET = "it-webhook-secret-0123456789abcdefghijklmnop";

    @Autowired protected BoardChecker checker;
    @Autowired protected BoardRepository boards;
    @Autowired protected PostingRepository postings;
    @Autowired protected TaggingWorker tagging;
    @Autowired protected AlertRepository alerts;
    @Autowired protected UserRepository users;
    @Autowired protected ProfileRepository profiles;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected WireMockServer fake;
    @Value("${local.server.port}") protected int port;

    @BeforeEach
    protected void stubTelegramAndGemini() {
        fake.stubFor(post(urlPathMatching("/bot[^/]+/sendMessage")).willReturn(okJson(
                "{\"ok\": true, \"result\": {\"message_id\": 900, \"date\": 1, \"chat\": {\"id\": 1, \"type\": \"private\"}}}")));
        fake.stubFor(post(urlPathMatching("/bot[^/]+/(answerCallbackQuery|editMessageText|editMessageReplyMarkup)"))
                .willReturn(okJson("{\"ok\": true, \"result\": true}")));
        // Tagging requests are refused (400, not retried), so the keyword fallback tags deterministically.
        fake.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .withRequestBody(containing("You tag job postings"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\": {\"status\": \"INVALID_ARGUMENT\"}}")));
    }

    /** A backend fresher in Bengaluru with Java, Spring Boot and PostgreSQL. */
    protected User activeUser(int minScore) {
        long tg = IDS.incrementAndGet();
        User user = users.create(tg, tg, "Pipeline", null, Instant.now());
        users.activate(user.id(), Instant.now());
        profiles.upsert(new Profile(user.id(), List.of(RoleCategory.BACKEND), List.of("Bengaluru"), false, false, 0,
                false, List.of("Java", "Spring Boot", "PostgreSQL"), List.of(), 2025, minScore, 10, null, null, Instant.now()));
        return users.findById(user.id()).orElseThrow();
    }

    /** A board whose first (baseline) check found nothing, so the next job is genuinely new. */
    protected Board boardWithBaseline() {
        String token = "pipe-" + UUID.randomUUID().toString().substring(0, 8);
        boards.insertIfAbsent(Source.GREENHOUSE, token, "Pipe " + token);
        Board board = boards.find(Source.GREENHOUSE, token).orElseThrow();
        fake.stubFor(get(urlPathEqualTo(path(board))).withQueryParam("content", equalTo("true"))
                .willReturn(okJson("{\"jobs\": [], \"meta\": {\"total\": 0}}")));
        checker.check(board);
        return reload(board);
    }

    /** Puts a fresher-friendly Java job on the board and checks it, as a tick would. Returns the posting id. */
    protected UUID newJob(Board board, String id, String title) {
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

    protected void stubList(Board board, String etag, String... jobs) {
        fake.stubFor(get(urlPathEqualTo(path(board))).withQueryParam("content", absent())
                .willReturn(okJson("{\"jobs\": [" + String.join(",", jobs) + "], \"meta\": {\"total\": " + jobs.length + "}}")
                        .withHeader("ETag", etag)));
    }

    protected static String path(Board board) {
        return "/v1/boards/" + board.token() + "/jobs";
    }

    protected Board reload(Board board) {
        return boards.findById(board.id()).orElseThrow();
    }

    protected Posting posting(UUID id) {
        return postings.findById(id).orElseThrow();
    }

    /** The alert for this user and posting, or null if there is none (yet). */
    protected Alert alert(User user, UUID postingId) {
        return jdbc.sql("select id from alerts where user_id = :u and posting_id = :p")
                .param("u", user.id()).param("p", postingId).query(UUID.class).optional()
                .flatMap(alerts::findById)
                .orElse(null);
    }

    protected Alert awaitAlert(User user, UUID postingId, Alert.Status status) {
        return await().atMost(Duration.ofSeconds(20))
                .until(() -> alert(user, postingId), a -> a != null && a.status() == status);
    }

    protected List<String> messagesTo(User user) {
        return messagesToChat(user.chatId());
    }

    protected List<String> messagesToChat(long chatId) {
        return fake.findAll(postRequestedFor(urlPathMatching("/bot[^/]+/sendMessage"))
                        .withRequestBody(matchingJsonPath("$.chat_id", equalTo(String.valueOf(chatId)))))
                .stream().map(LoggedRequest::getBodyAsString).toList();
    }

    /** Taps an inline button, delivered to the webhook exactly as Telegram would. */
    protected void tap(User user, String data) throws Exception {
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
