package io.github.yuvrajtiwary.earlybird.telegram.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.common.RateLimiter.Bandwidth;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * A thin client for the Telegram Bot API: only the methods EarlyBird uses, no bot framework.
 *
 * <ul>
 *   <li>Requests and responses are snake_case JSON mapped to records ({@link #JSON}).
 *   <li>Sends go through a global token bucket ({@code TELEGRAM_GLOBAL_RATE_PER_SEC}, default 25/s, under
 *       Telegram's ~30/s), and a 429 is waited out once ({@code retry_after}, up to 30 s).
 *   <li>The token is part of every URL, so errors are rebuilt without the HTTP layer's message and any
 *       stray occurrence of the token is redacted.
 * </ul>
 */
public class TelegramApi {

    /** Telegram's JSON: snake_case names, null fields left out of requests. */
    public static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    public static final int MAX_MESSAGE_LENGTH = 4096;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    private static final String GLOBAL_BUCKET = "tg:global";

    private final RestClient http;
    private final URI baseUrl;
    private final String token;
    private final RateLimiter rateLimiter;
    private final Bandwidth globalRate;

    public TelegramApi(URI baseUrl, String token, RateLimiter rateLimiter, int globalRatePerSecond) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(40)); // long polling waits up to 25 s for updates
        this.http = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = baseUrl;
        this.token = token;
        this.rateLimiter = rateLimiter;
        this.globalRate = Bandwidth.perSecond(globalRatePerSecond);
    }

    // ------------------------------------------------------------------------------------ messages

    public Message sendMessage(long chatId, String html, InlineKeyboardMarkup keyboard) {
        return sendMessage(chatId, html, keyboard, null);
    }

    /**
     * @param previewImageUrl shown as a large picture above the text (alerts use the company's logo card), or null
     *                        for no preview. Links inside the text never get a preview of their own either way.
     */
    public Message sendMessage(long chatId, String html, InlineKeyboardMarkup keyboard, String previewImageUrl) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("text", truncate(html));
        params.put("parse_mode", "HTML");
        params.put("link_preview_options", previewImageUrl == null
                ? Map.of("is_disabled", true)
                : Map.of("url", previewImageUrl, "prefer_large_media", true, "show_above_text", true));
        if (keyboard != null) {
            params.put("reply_markup", keyboard);
        }
        return send("sendMessage", params, JSON.constructType(Message.class));
    }

    public void editMessageText(long chatId, long messageId, String html, InlineKeyboardMarkup keyboard) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        params.put("text", truncate(html));
        params.put("parse_mode", "HTML");
        params.put("link_preview_options", Map.of("is_disabled", true));
        if (keyboard != null) {
            params.put("reply_markup", keyboard);
        }
        ignoringNotModified(() -> send("editMessageText", params, JSON.constructType(JsonNode.class)));
    }

    public void editMessageReplyMarkup(long chatId, long messageId, InlineKeyboardMarkup keyboard) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        params.put("reply_markup", keyboard == null ? new InlineKeyboardMarkup(List.of()) : keyboard);
        ignoringNotModified(() -> send("editMessageReplyMarkup", params, JSON.constructType(JsonNode.class)));
    }

    /** Stops the button's loading spinner; {@code text} (optional) shows as a small toast. */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("callback_query_id", callbackQueryId);
        if (text != null) {
            params.put("text", text);
        }
        call("answerCallbackQuery", params, JSON.constructType(Boolean.class));
    }

    // ------------------------------------------------------------------------------------ updates

    /** Long polling: waits up to {@code timeoutSeconds} for new updates. Local development only. */
    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        return call("getUpdates", Map.of(
                "offset", offset,
                "timeout", timeoutSeconds,
                "allowed_updates", List.of("message", "callback_query")),
                JSON.getTypeFactory().constructCollectionType(List.class, Update.class));
    }

    public void setWebhook(String url, String secretToken) {
        call("setWebhook", Map.of(
                "url", url,
                "secret_token", secretToken,
                "allowed_updates", List.of("message", "callback_query"),
                "max_connections", 10), JSON.constructType(Boolean.class));
    }

    public void deleteWebhook() {
        call("deleteWebhook", Map.of("drop_pending_updates", false), JSON.constructType(Boolean.class));
    }

    public void setMyCommands(List<BotCommand> commands) {
        call("setMyCommands", Map.of("commands", commands), JSON.constructType(Boolean.class));
    }

    // ------------------------------------------------------------------------------------ files

    public TgFile getFile(String fileId) {
        return call("getFile", Map.of("file_id", fileId), JSON.constructType(TgFile.class));
    }

    /** Downloads a file found with {@link #getFile}; fails if it's larger than {@code maxBytes}. */
    public byte[] downloadFile(String filePath, long maxBytes) {
        URI uri = URI.create(baseUrl + "/file/bot" + token + "/" + filePath);
        try {
            return http.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new TelegramException(response.getStatusCode().value(), "file download failed", null);
                }
                try (InputStream in = response.getBody()) {
                    byte[] bytes = in.readNBytes((int) maxBytes + 1);
                    if (bytes.length > maxBytes) {
                        throw new TelegramException(413, "file larger than " + maxBytes + " bytes", null);
                    }
                    return bytes;
                }
            }, true);
        } catch (TelegramException e) {
            throw e;
        } catch (RestClientException e) {
            throw network(e);
        }
    }

    // ------------------------------------------------------------------------------------ plumbing

    /** A rate-limited call that waits out one 429. */
    private <T> T send(String method, Map<String, Object> params, JavaType resultType) {
        acquireGlobal();
        try {
            return call(method, params, resultType);
        } catch (TelegramException e) {
            if (e.errorCode() == 429 && e.retryAfter() != null && e.retryAfter().compareTo(MAX_RETRY_AFTER) <= 0) {
                sleep(e.retryAfter());
                acquireGlobal();
                return call(method, params, resultType);
            }
            throw e;
        }
    }

    private <T> T call(String method, Map<String, Object> params, JavaType resultType) {
        URI uri = URI.create(baseUrl + "/bot" + token + "/" + method);
        try {
            String body = JSON.writeValueAsString(params);
            JsonNode response = http.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, res) -> readTree(res.getBody()), true);
            if (!response.path("ok").asBoolean(false)) {
                JsonNode retry = response.path("parameters").path("retry_after");
                throw new TelegramException(response.path("error_code").asInt(0),
                        redact(response.path("description").asString("unknown error")),
                        retry.isNumber() ? Duration.ofSeconds(retry.asLong()) : null);
            }
            return JSON.treeToValue(response.path("result"), resultType);
        } catch (TelegramException e) {
            throw e;
        } catch (JacksonException e) {
            throw new TelegramException(0, method + ": unreadable response", null);
        } catch (RestClientException e) {
            throw network(e);
        }
    }

    private JsonNode readTree(InputStream body) throws IOException {
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            return JSON.createObjectNode(); // e.g. an HTML error page from a proxy
        }
    }

    private TelegramException network(RestClientException e) {
        return new TelegramException(0, "network error: " + e.getMostSpecificCause().getClass().getSimpleName(), null);
    }

    private String redact(String text) {
        return token == null || token.isEmpty() ? text : text.replace(token, "<token>");
    }

    private void acquireGlobal() {
        try {
            rateLimiter.acquire(GLOBAL_BUCKET, globalRate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException(0, "interrupted", null);
        }
    }

    private static void ignoringNotModified(Runnable edit) {
        try {
            edit.run();
        } catch (TelegramException e) {
            // Editing a message to exactly what it already shows (e.g. a double tap) is harmless.
            if (!(e.errorCode() == 400 && e.getMessage().contains("message is not modified"))) {
                throw e;
            }
        }
    }

    private static String truncate(String html) {
        return html.length() <= MAX_MESSAGE_LENGTH ? html : html.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException(0, "interrupted", null);
        }
    }
}
