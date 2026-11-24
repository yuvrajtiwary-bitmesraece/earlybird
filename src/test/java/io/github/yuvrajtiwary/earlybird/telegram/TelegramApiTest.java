package io.github.yuvrajtiwary.earlybird.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.yuvrajtiwary.earlybird.common.TokenBucketRateLimiter;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardButton;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardMarkup;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import io.github.yuvrajtiwary.earlybird.telegram.api.Update;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The Bot API client against a fake Telegram. Payloads follow https://core.telegram.org/bots/api. */
class TelegramApiTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String TOKEN = "123456:SECRET-test-token";
    private final TelegramApi api = new TelegramApi(URI.create(wm.baseUrl()), TOKEN,
            new TokenBucketRateLimiter(Clock.systemUTC()), 25);

    private static String path(String method) {
        return "/bot" + TOKEN + "/" + method;
    }

    @Test
    void sendsHtmlMessagesWithSnakeCaseKeyboards() {
        wm.stubFor(post(urlEqualTo(path("sendMessage"))).willReturn(okJson("""
                {"ok": true, "result": {"message_id": 77, "chat": {"id": 42, "type": "private"}, "date": 1}}""")));
        var keyboard = new InlineKeyboardMarkup(List.of(List.of(InlineKeyboardButton.callback("Done", "r:done"))));

        var sent = api.sendMessage(42, "<b>Hi</b>", keyboard);

        assertThat(sent.messageId()).isEqualTo(77);
        wm.verify(postRequestedFor(urlEqualTo(path("sendMessage")))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("42")))
                .withRequestBody(matchingJsonPath("$.parse_mode", equalTo("HTML")))
                .withRequestBody(matchingJsonPath("$.link_preview_options.is_disabled", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.reply_markup.inline_keyboard[0][0].callback_data", equalTo("r:done"))));
    }

    @Test
    void parsesUpdates() {
        wm.stubFor(post(urlEqualTo(path("getUpdates"))).willReturn(okJson("""
                {"ok": true, "result": [
                  {"update_id": 10, "message": {"message_id": 1, "date": 1, "text": "/start",
                    "from": {"id": 42, "is_bot": false, "first_name": "Asha", "username": "asha_dev"},
                    "chat": {"id": 42, "type": "private"}}},
                  {"update_id": 11, "callback_query": {"id": "cb1", "data": "r:backend",
                    "from": {"id": 42, "is_bot": false, "first_name": "Asha"},
                    "message": {"message_id": 2, "date": 1, "chat": {"id": 42, "type": "private"}}}},
                  {"update_id": 12, "message": {"message_id": 3, "date": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "Asha"}, "chat": {"id": 42, "type": "private"},
                    "document": {"file_id": "F1", "file_unique_id": "U1", "file_name": "cv.pdf",
                      "mime_type": "application/pdf", "file_size": 12345}}}
                ]}""")));

        List<Update> updates = api.getUpdates(0, 0);

        assertThat(updates).extracting(Update::updateId).containsExactly(10L, 11L, 12L);
        assertThat(updates.get(0).message().text()).isEqualTo("/start");
        assertThat(updates.get(0).sender().firstName()).isEqualTo("Asha");
        assertThat(updates.get(0).message().chat().isPrivate()).isTrue();
        assertThat(updates.get(1).callbackQuery().data()).isEqualTo("r:backend");
        assertThat(updates.get(2).message().document().mimeType()).isEqualTo("application/pdf");
        assertThat(updates.get(2).message().document().fileSize()).isEqualTo(12345L);
    }

    @Test
    void errorsCarryTelegramsCodeAndNeverTheToken() {
        wm.stubFor(post(urlEqualTo(path("sendMessage"))).willReturn(aResponse().withStatus(403).withBody("""
                {"ok": false, "error_code": 403, "description": "Forbidden: bot was blocked by the user"}""")));

        assertThatThrownBy(() -> api.sendMessage(42, "hi", null))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(403);
                    assertThat(e.blockedByUser()).isTrue();
                    assertThat(e.getMessage()).doesNotContain(TOKEN);
                });
    }

    @Test
    void aNetworkErrorDoesNotLeakTheTokenFromTheUrl() {
        var unreachable = new TelegramApi(URI.create("http://127.0.0.1:1"), TOKEN,
                new TokenBucketRateLimiter(Clock.systemUTC()), 25);

        assertThatThrownBy(() -> unreachable.sendMessage(42, "hi", null))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.getMessage()).startsWith("network error").doesNotContain(TOKEN, "SECRET");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void waitsOutA429OnceThenSends() {
        wm.stubFor(post(urlEqualTo(path("sendMessage"))).inScenario("flood").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withBody("""
                        {"ok": false, "error_code": 429, "description": "Too Many Requests: retry after 1",
                         "parameters": {"retry_after": 1}}""")).willSetStateTo("ok"));
        wm.stubFor(post(urlEqualTo(path("sendMessage"))).inScenario("flood").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"ok\": true, \"result\": {\"message_id\": 5, \"date\": 1}}")));

        assertThat(api.sendMessage(42, "hi", null).messageId()).isEqualTo(5);
        wm.verify(2, postRequestedFor(urlEqualTo(path("sendMessage"))));
    }

    @Test
    void editingToTheSameContentIsNotAnError() {
        wm.stubFor(post(urlEqualTo(path("editMessageText"))).willReturn(aResponse().withStatus(400).withBody("""
                {"ok": false, "error_code": 400, "description": "Bad Request: message is not modified"}""")));

        api.editMessageText(42, 7, "same", null); // no exception
    }

    @Test
    void downloadsFilesWithinTheSizeLimit() {
        wm.stubFor(post(urlEqualTo(path("getFile"))).willReturn(okJson("""
                {"ok": true, "result": {"file_id": "F1", "file_size": 4, "file_path": "documents/file_1.pdf"}}""")));
        wm.stubFor(get(urlEqualTo("/file/bot" + TOKEN + "/documents/file_1.pdf"))
                .willReturn(aResponse().withBody(new byte[] {1, 2, 3, 4})));

        String filePath = api.getFile("F1").filePath();

        assertThat(api.downloadFile(filePath, 10)).containsExactly(1, 2, 3, 4);
        assertThatThrownBy(() -> api.downloadFile(filePath, 3)).isInstanceOf(TelegramException.class);
    }
}
