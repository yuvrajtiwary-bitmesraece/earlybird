package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.common.Base62;
import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.common.RateLimiter.Bandwidth;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.profiles.ProfileService;
import io.github.yuvrajtiwary.earlybird.telegram.Commands.Command;
import io.github.yuvrajtiwary.earlybird.telegram.Commands.SkillsRequest;
import io.github.yuvrajtiwary.earlybird.telegram.Session.State;
import io.github.yuvrajtiwary.earlybird.telegram.api.CallbackQuery;
import io.github.yuvrajtiwary.earlybird.telegram.api.Message;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import io.github.yuvrajtiwary.earlybird.telegram.api.TgUser;
import io.github.yuvrajtiwary.earlybird.telegram.api.Update;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes each update: commands, documents (resumes) and button taps. Private chats only, and at most 30
 * commands a minute per user (spec 3.11). Account commands (/pause, /unpause, /stats, /delete, /cancel,
 * /skills) are handled here; multi-step flows live in {@link ProfileFlows} and {@link ResumeUpload}.
 */
class Bot {

    private static final Logger log = LoggerFactory.getLogger(Bot.class);

    /** 30 commands a minute: a burst of 30, refilling at one every 2 seconds. */
    static final Bandwidth PER_USER = new Bandwidth(30, 0.5);
    private static final Bandwidth SLOW_DOWN_NOTICE = new Bandwidth(1, 1.0 / 30);

    private final TelegramApi api;
    private final UserRepository users;
    private final AlertRepository alerts;
    private final SessionStore sessions;
    private final ProfileService profiles;
    private final ProfileFlows flows;
    private final ResumeUpload resume;
    private final AdminCommands admin;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final Long adminChatId;

    Bot(TelegramApi api, UserRepository users, AlertRepository alerts, SessionStore sessions, ProfileService profiles,
            ProfileFlows flows, ResumeUpload resume, AdminCommands admin, RateLimiter rateLimiter, Clock clock,
            Long adminChatId) {
        this.api = api;
        this.users = users;
        this.alerts = alerts;
        this.sessions = sessions;
        this.profiles = profiles;
        this.flows = flows;
        this.resume = resume;
        this.admin = admin;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.adminChatId = adminChatId;
    }

    void handle(Update update) {
        TgUser from = update.sender();
        if (from == null || from.isBot()) {
            return;
        }
        try {
            if (update.message() != null) {
                onMessage(from, update.message());
            } else if (update.callbackQuery() != null) {
                onCallback(from, update.callbackQuery());
            }
        } catch (TelegramException e) {
            if (e.blockedByUser()) {
                users.findByTelegramUserId(from.id()).ifPresent(u -> users.updateStatus(u.id(), User.Status.BLOCKED));
                log.info("User {} has blocked the bot", from.id());
                return;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------- messages

    private void onMessage(TgUser from, Message message) {
        if (message.chat() == null || !message.chat().isPrivate()) {
            return; // groups and channels are ignored
        }
        long chatId = message.chat().id();
        if (!allowed(from.id(), chatId)) {
            return;
        }
        Command command = Commands.parse(message.text());
        if (command != null && command.name().equals("start")) {
            flows.start(from, chatId);
            return;
        }
        Optional<User> found = users.findByTelegramUserId(from.id());
        if (found.isEmpty()) {
            api.sendMessage(chatId, Texts.needStart(), null);
            return;
        }
        User user = reactivateIfBlocked(found.get());
        users.touch(user.id(), clock.instant());

        if (message.document() != null) {
            resume.onDocument(user, message);
            return;
        }
        if (command == null) {
            api.sendMessage(chatId, Texts.notACommand(), null);
            return;
        }
        boolean onboarded = ProfileFlows.onboarded(user);
        switch (command.name()) {
            case "help" -> api.sendMessage(chatId, Texts.help(), null);
            case "prefs" -> ifOnboarded(onboarded, chatId, () -> flows.showPrefs(user, chatId));
            case "skills" -> ifOnboarded(onboarded, chatId, () -> skills(user, chatId, command.args()));
            case "upload" -> ifOnboarded(onboarded, chatId, () -> resume.prompt(user, chatId));
            case "pause" -> ifOnboarded(onboarded, chatId, () -> {
                users.updateStatus(user.id(), User.Status.PAUSED);
                api.sendMessage(chatId, Texts.paused(), null);
            });
            case "unpause" -> ifOnboarded(onboarded, chatId, () -> {
                users.updateStatus(user.id(), User.Status.ACTIVE);
                api.sendMessage(chatId, Texts.unpaused(), null);
            });
            case "stats" -> api.sendMessage(chatId,
                    Texts.stats(alerts.statsForUser(user.id(), clock.instant().minus(Duration.ofDays(7)))), null);
            case "delete" -> api.sendMessage(chatId, Texts.confirmDelete(), Keyboards.confirmDelete());
            case "cancel" -> cancel(user, chatId);
            case "admin" -> adminOnly(from, chatId, () -> admin.status(chatId));
            case "admin_tick" -> adminOnly(from, chatId, () -> admin.tick(chatId));
            default -> api.sendMessage(chatId, Texts.unknownCommand(), null);
        }
    }

    private void skills(User user, long chatId, String args) {
        SkillsRequest request = Commands.parseSkills(args);
        var profile = switch (request.action()) {
            case ADD -> profiles.addSkills(user.id(), request.skills());
            case REMOVE -> profiles.removeSkills(user.id(), request.skills());
            case LIST -> profiles.find(user.id()).orElseThrow();
        };
        api.sendMessage(chatId, Texts.skills(profile.skills()), null);
    }

    private void cancel(User user, long chatId) {
        State state = sessions.load(user.id()).state();
        if (state.onboarding()) {
            api.sendMessage(chatId, "Setup paused. Send /start to continue where you left off.", null);
        } else if (state != State.IDLE) {
            sessions.save(user.id(), Session.idle());
            api.sendMessage(chatId, "Cancelled.", null);
        } else {
            api.sendMessage(chatId, "Nothing to cancel.", null);
        }
    }

    // ------------------------------------------------------------------------------------- buttons

    private void onCallback(TgUser from, CallbackQuery cq) {
        String toast = null;
        try {
            if (cq.message() == null || cq.message().chat() == null || !cq.message().chat().isPrivate()
                    || cq.data() == null || !cq.data().contains(":")) {
                return;
            }
            if (!rateLimiter.tryAcquire("bot:cmd:" + from.id(), PER_USER).isZero()) {
                toast = Texts.slowDown();
                return;
            }
            Optional<User> found = users.findByTelegramUserId(from.id());
            if (found.isEmpty()) {
                toast = Texts.needStart();
                return;
            }
            User user = reactivateIfBlocked(found.get());
            users.touch(user.id(), clock.instant());
            long chatId = cq.message().chat().id();
            if (cq.data().startsWith("del:")) {
                if (cq.data().equals("del:yes")) {
                    users.delete(user.id()); // cascades to profile, session and alerts
                    log.info("User {} deleted their data", user.id());
                    api.editMessageText(chatId, cq.message().messageId(), Texts.deleted(), null);
                } else {
                    api.editMessageText(chatId, cq.message().messageId(), "Kept everything.", null);
                }
                return;
            }
            if (cq.data().startsWith("fb:")) {
                toast = feedback(user, cq.data());
                return;
            }
            toast = flows.onCallback(user, cq);
        } finally {
            try {
                api.answerCallbackQuery(cq.id(), toast);
            } catch (TelegramException e) {
                log.debug("answerCallbackQuery failed: {}", e.getMessage()); // e.g. the tap is too old
            }
        }
    }

    /** 👍 / 👎 on an alert: {@code fb:r:<alert id in base62>} or {@code fb:n:...}. Stored for /stats. */
    private String feedback(User user, String data) {
        String[] parts = data.split(":");
        if (parts.length != 3 || !(parts[1].equals("r") || parts[1].equals("n"))) {
            return ProfileFlows.STALE;
        }
        UUID alertId;
        try {
            alertId = Base62.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return ProfileFlows.STALE;
        }
        boolean relevant = parts[1].equals("r");
        if (!alerts.setFeedback(alertId, user.id(), relevant ? Alert.Feedback.RELEVANT : Alert.Feedback.NOT_RELEVANT,
                clock.instant())) {
            return "That alert is no longer available.";
        }
        return relevant ? "Thanks! Marked as relevant 👍" : "Thanks! That helps tune your matches.";
    }

    // ------------------------------------------------------------------------------------- helpers

    private boolean allowed(long telegramUserId, long chatId) {
        if (rateLimiter.tryAcquire("bot:cmd:" + telegramUserId, PER_USER).isZero()) {
            return true;
        }
        if (rateLimiter.tryAcquire("bot:slow:" + telegramUserId, SLOW_DOWN_NOTICE).isZero()) {
            api.sendMessage(chatId, Texts.slowDown(), null); // at most one notice per 30 s
        }
        return false;
    }

    private User reactivateIfBlocked(User user) {
        if (user.status() != User.Status.BLOCKED) {
            return user;
        }
        User.Status status = ProfileFlows.onboarded(user) ? User.Status.ACTIVE : User.Status.ONBOARDING;
        users.updateStatus(user.id(), status);
        return users.findById(user.id()).orElse(user);
    }

    private void ifOnboarded(boolean onboarded, long chatId, Runnable action) {
        if (onboarded) {
            action.run();
        } else {
            api.sendMessage(chatId, Texts.finishSetupFirst(), null);
        }
    }

    private void adminOnly(TgUser from, long chatId, Runnable action) {
        if (adminChatId != null && adminChatId == from.id()) {
            action.run();
        } else {
            api.sendMessage(chatId, Texts.unknownCommand(), null); // don't reveal that admin commands exist
        }
    }
}
