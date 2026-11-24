package io.github.yuvrajtiwary.earlybird.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.yuvrajtiwary.earlybird.persistence.BotSessionRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.ProfileRepository;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import io.github.yuvrajtiwary.earlybird.support.TestPdfs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * The bot end to end, driven exactly as Telegram drives it in production: updates POSTed to the webhook.
 * Telegram and Gemini are WireMock; the database is real. Each test uses its own Telegram user.
 */
@IntegrationTest
class BotIT {

    private static final String SECRET = "it-webhook-secret-0123456789abcdefghijklmnop";
    private static final AtomicLong IDS = new AtomicLong(7_000_000);
    private static final String GEMINI = "/v1beta/models/.*:generateContent";

    @Autowired WireMockServer fake;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired BotSessionRepository sessions;
    @Value("${local.server.port}") int port;

    private final long telegramUserId = IDS.incrementAndGet();

    @BeforeEach
    void stubTelegramAndGemini() {
        fake.stubFor(post(urlPathMatching("/bot[^/]+/sendMessage")).willReturn(okJson(
                "{\"ok\": true, \"result\": {\"message_id\": 500, \"date\": 1, \"chat\": {\"id\": 1, \"type\": \"private\"}}}")));
        fake.stubFor(post(urlPathMatching(
                "/bot[^/]+/(editMessageText|editMessageReplyMarkup|answerCallbackQuery|setWebhook|setMyCommands)"))
                .willReturn(okJson("{\"ok\": true, \"result\": true}")));
        fake.stubFor(post(urlPathMatching(GEMINI)).willReturn(okJson(Fixtures.read("gemini/resume-response.json"))));
    }

    // ------------------------------------------------------------------------------------------ tests

    @Test
    void aNewUserOnboardsUploadsAResumeAndAlertsTurnOn() {
        String fileId = "F" + telegramUserId;
        stubResumeFile(fileId);

        send(message("/start"));
        send(tap("r:backend"));
        send(tap("r:fullstack"));
        send(tap("r:done"));
        send(tap("l:blr"));
        send(tap("l:rin"));
        send(tap("l:done"));
        send(tap("x:0"));
        send(tap("i:y"));
        send(document(fileId));

        User user = awaitStatus(User.Status.ACTIVE);
        Profile p = profiles.findByUserId(user.id()).orElseThrow();
        assertThat(p.roles()).containsExactly(RoleCategory.BACKEND, RoleCategory.FULLSTACK);
        assertThat(p.cities()).containsExactly("Bengaluru");
        assertThat(p.remoteIndia()).isTrue();
        assertThat(p.remoteWorldwide()).isFalse();
        assertThat(p.yearsExperience()).isZero();
        assertThat(p.includeInternships()).isTrue();
        assertThat(p.skills()).contains("Spring Boot", "Kubernetes", "JavaScript").doesNotContain("springboot", "k8s");
        assertThat(p.graduationYear()).isEqualTo(2025);
        assertThat(p.resumeSha256()).hasSize(64);
        assertThat(user.consentAt()).isNotNull();
        assertThat(messagesTo(telegramUserId)).anyMatch(m -> m.contains("Alerts are on"));
    }

    @Test
    void contactDetailsInTheResumeNeverReachGemini() {
        String fileId = "F" + telegramUserId;
        stubResumeFile(fileId);
        onboardWithoutResume();
        send(message("/upload"));
        send(document(fileId));

        await().untilAsserted(() -> assertThat(profileOf().skills()).contains("Spring Boot"));
        List<String> geminiBodies = fake.findAll(postRequestedFor(urlPathMatching(GEMINI))).stream()
                .map(LoggedRequest::getBodyAsString).toList();
        assertThat(geminiBodies).isNotEmpty().allSatisfy(body -> assertThat(body)
                .doesNotContain("asha.kumari@example.com", "98765", "Asha Kumari", "linkedin.com", "560001"));
    }

    @Test
    void theSameUpdateDeliveredTwiceIsHandledOnce() {
        String start = message("/start");
        send(start);
        send(start); // Telegram re-delivers webhook updates it isn't sure we got

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(10))
                .until(() -> messagesTo(telegramUserId).stream().filter(m -> m.contains("Step 1 of 5")).count() == 1);
    }

    @Test
    void requestsWithoutTheRightSecretAreRejected() throws Exception {
        assertThat(postToWebhook(message("/start"), "wrong-secret")).isEqualTo(401);
        assertThat(postToWebhook(message("/start"), null)).isEqualTo(401);
        assertThat(users.findByTelegramUserId(telegramUserId)).isEmpty();
    }

    @Test
    void groupChatsAreIgnored() {
        send(message("/start").replace("\"type\": \"private\"", "\"type\": \"group\""));

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> users.findByTelegramUserId(telegramUserId).isEmpty());
    }

    @Test
    void skillsCanBeAddedAndRemoved() {
        onboardWithoutResume();

        send(message("/skills add k8s, springboot"));
        await().untilAsserted(() -> assertThat(profileOf().skills()).containsExactly("Kubernetes", "Spring Boot"));

        send(message("/skills remove kubernetes"));
        await().untilAsserted(() -> assertThat(profileOf().skills()).containsExactly("Spring Boot"));
    }

    @Test
    void preferencesCanBeChanged() {
        onboardWithoutResume();

        send(message("/prefs"));
        send(tap("p:score"));
        send(tap("s:70"));
        send(tap("p:locs"));
        send(tap("l:pune"));
        send(tap("l:done"));

        await().untilAsserted(() -> {
            Profile p = profileOf();
            assertThat(p.minScore()).isEqualTo(70);
            assertThat(p.cities()).containsExactlyInAnyOrder("Bengaluru", "Pune");
        });
    }

    @Test
    void pauseAndUnpause() {
        onboardWithoutResume();

        send(message("/pause"));
        awaitStatus(User.Status.PAUSED);
        send(message("/unpause"));
        awaitStatus(User.Status.ACTIVE);
    }

    @Test
    void deleteErasesEverything() {
        onboardWithoutResume();
        long userId = users.findByTelegramUserId(telegramUserId).orElseThrow().id();

        send(message("/delete"));
        send(tap("del:yes"));

        await().untilAsserted(() -> assertThat(users.findByTelegramUserId(telegramUserId)).isEmpty());
        assertThat(profiles.findByUserId(userId)).isEmpty();
        assertThat(sessions.find(userId)).isEmpty();
        assertThat(messagesTo(telegramUserId)).anyMatch(m -> m.contains("has been deleted"));
    }

    @Test
    void anOldButtonIsAnsweredButChangesNothing() {
        onboardWithoutResume();

        send(tap("r:backend")); // from the onboarding message, long finished

        await().untilAsserted(() -> fake.verify(postRequestedFor(urlPathMatching("/bot[^/]+/answerCallbackQuery"))
                .withRequestBody(matchingJsonPath("$.text", equalTo(ProfileFlows.STALE)))));
        assertThat(profileOf().roles()).containsExactly(RoleCategory.BACKEND);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void onboardWithoutResume() {
        send(message("/start"));
        send(tap("r:backend"));
        send(tap("r:done"));
        send(tap("l:blr"));
        send(tap("l:done"));
        send(tap("x:0"));
        send(tap("i:n"));
        send(tap("cv:skip"));
        awaitStatus(User.Status.ACTIVE);
    }

    private User awaitStatus(User.Status status) {
        return await().atMost(Duration.ofSeconds(20)).until(
                () -> users.findByTelegramUserId(telegramUserId).orElse(null),
                u -> u != null && u.status() == status);
    }

    private Profile profileOf() {
        long userId = users.findByTelegramUserId(telegramUserId).orElseThrow().id();
        return profiles.findByUserId(userId).orElseThrow();
    }

    private void stubResumeFile(String fileId) {
        fake.stubFor(post(urlPathMatching("/bot[^/]+/getFile"))
                .withRequestBody(matchingJsonPath("$.file_id", equalTo(fileId)))
                .willReturn(okJson("{\"ok\": true, \"result\": {\"file_id\": \"" + fileId
                        + "\", \"file_path\": \"documents/" + fileId + ".pdf\"}}")));
        fake.stubFor(get(urlPathMatching("/file/bot[^/]+/documents/" + fileId + "\\.pdf"))
                .willReturn(aResponse().withBody(TestPdfs.withLines(TestPdfs.FAKE_RESUME))));
    }

    /** Texts of all sendMessage calls to this chat. */
    private List<String> messagesTo(long chatId) {
        return fake.findAll(postRequestedFor(urlPathMatching("/bot[^/]+/sendMessage"))
                        .withRequestBody(matchingJsonPath("$.chat_id", equalTo(String.valueOf(chatId)))))
                .stream().map(LoggedRequest::getBodyAsString).toList();
    }

    private String from() {
        return "{\"id\": " + telegramUserId + ", \"is_bot\": false, \"first_name\": \"Test\", \"username\": \"tester\"}";
    }

    private String chat() {
        return "{\"id\": " + telegramUserId + ", \"type\": \"private\"}";
    }

    private String message(String text) {
        return "{\"update_id\": " + IDS.incrementAndGet() + ", \"message\": {\"message_id\": " + IDS.incrementAndGet()
                + ", \"date\": 1, \"text\": \"" + text + "\", \"from\": " + from() + ", \"chat\": " + chat() + "}}";
    }

    private String tap(String data) {
        return "{\"update_id\": " + IDS.incrementAndGet() + ", \"callback_query\": {\"id\": \"cb" + IDS.incrementAndGet()
                + "\", \"data\": \"" + data + "\", \"from\": " + from() + ", \"message\": {\"message_id\": 99, "
                + "\"date\": 1, \"chat\": " + chat() + "}}}";
    }

    private String document(String fileId) {
        return "{\"update_id\": " + IDS.incrementAndGet() + ", \"message\": {\"message_id\": " + IDS.incrementAndGet()
                + ", \"date\": 1, \"from\": " + from() + ", \"chat\": " + chat() + ", \"document\": {\"file_id\": \""
                + fileId + "\", \"file_unique_id\": \"U" + fileId + "\", \"file_name\": \"resume.pdf\", "
                + "\"mime_type\": \"application/pdf\", \"file_size\": 2048}}}";
    }

    private void send(String updateJson) {
        try {
            assertThat(postToWebhook(updateJson, SECRET)).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int postToWebhook(String updateJson, String secret) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + WebhookController.PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(updateJson));
        if (secret != null) {
            request.header(WebhookController.SECRET_HEADER, secret);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
