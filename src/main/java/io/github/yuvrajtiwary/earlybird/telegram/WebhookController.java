package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.Update;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;

/**
 * Production entry point ({@code TELEGRAM_MODE=webhook}): Telegram POSTs each update here.
 *
 * <ul>
 *   <li>The {@code X-Telegram-Bot-Api-Secret-Token} header must equal our secret, compared in constant time
 *       so response timing reveals nothing about it. Anyone else gets 401.
 *   <li>Answers 200 at once and handles the update on the {@link UpdateDispatcher}; Telegram retries slow
 *       or failed webhook calls, which would otherwise cause duplicate work.
 * </ul>
 */
@RestController
@ConditionalOnRole(Role.BOT)
@ConditionalOnProperty(name = "earlybird.telegram.mode", havingValue = "webhook")
class WebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    static final String PATH = "/telegram/webhook";

    private final UpdateDispatcher dispatcher;
    private final byte[] secret;

    WebhookController(UpdateDispatcher dispatcher, EarlyBirdProperties properties) {
        this.dispatcher = dispatcher;
        this.secret = properties.telegram().webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(PATH)
    ResponseEntity<Void> receive(@RequestHeader(name = SECRET_HEADER, required = false) String givenSecret,
            @RequestBody String body) {
        if (!secretMatches(secret, givenSecret)) {
            return ResponseEntity.status(401).build();
        }
        Update update;
        try {
            update = TelegramApi.JSON.readValue(body, Update.class);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().build();
        }
        return dispatcher.dispatch(update) ? ResponseEntity.ok().build() : ResponseEntity.status(503).build();
    }

    /** Constant-time comparison: the time taken doesn't depend on how many leading characters match. */
    static boolean secretMatches(byte[] expected, String given) {
        return given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
    }
}
