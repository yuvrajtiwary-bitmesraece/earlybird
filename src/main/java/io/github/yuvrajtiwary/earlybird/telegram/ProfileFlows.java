package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.matching.RecentMatches;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.profiles.LocationChoice;
import io.github.yuvrajtiwary.earlybird.profiles.ProfileService;
import io.github.yuvrajtiwary.earlybird.telegram.Session.Draft;
import io.github.yuvrajtiwary.earlybird.telegram.Session.State;
import io.github.yuvrajtiwary.earlybird.telegram.api.CallbackQuery;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TgUser;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Onboarding (spec 1.1: roles → locations → experience → internships → resume → summary) and the /prefs
 * editors, which reuse the same pickers. State lives in {@link Session}, so a redeploy mid-way loses nothing.
 * The profile row is written after step 4, so the resume step (5) has somewhere to save skills; the user
 * becomes {@code active}, and alerts start, only when they finish.
 */
class ProfileFlows {

    static final String STALE = "That menu is out of date.";

    private final TelegramApi api;
    private final UserRepository users;
    private final SessionStore sessions;
    private final ProfileService profiles;
    private final Clock clock;
    private final ObjectProvider<RecentMatches> recentMatches;

    ProfileFlows(TelegramApi api, UserRepository users, SessionStore sessions, ProfileService profiles, Clock clock,
            ObjectProvider<RecentMatches> recentMatches) {
        this.api = api;
        this.users = users;
        this.sessions = sessions;
        this.profiles = profiles;
        this.clock = clock;
        this.recentMatches = recentMatches;
    }

    static boolean onboarded(User user) {
        return user.consentAt() != null;
    }

    /**
     * After onboarding or a change that affects matching: up to 3 matches from the last 48 h (the matcher role;
     * absent in a bot-only process). They arrive as normal alerts, labelled "Recent match".
     */
    void sendRecentMatches(User user) {
        recentMatches.ifAvailable(r -> r.sendTo(user.id()));
    }

    // ----------------------------------------------------------------------------------------- /start

    void start(TgUser from, long chatId) {
        User user = users.findByTelegramUserId(from.id())
                .orElseGet(() -> users.create(from.id(), chatId, from.firstName(), from.username(), clock.instant()));
        if (user.status() == User.Status.BLOCKED) {
            users.updateStatus(user.id(), onboarded(user) ? User.Status.ACTIVE : User.Status.ONBOARDING);
        }
        Session session = sessions.load(user.id());
        if (onboarded(user) && !session.state().onboarding()) {
            profiles.find(user.id()).ifPresent(p -> api.sendMessage(chatId, Texts.alertsOn(p), null));
            return;
        }
        if (!session.state().onboarding()) {
            session = new Session(State.ONBOARDING_ROLES, Draft.empty());
            sessions.save(user.id(), session);
            api.sendMessage(chatId, Texts.welcome(from.firstName()), null);
        }
        sendStep(chatId, session);
    }

    /** (Re)sends the current onboarding step, e.g. when /start is sent again half-way. */
    private void sendStep(long chatId, Session session) {
        Draft d = session.draft();
        switch (session.state()) {
            case ONBOARDING_ROLES -> api.sendMessage(chatId, Texts.stepRoles(), Keyboards.roles(roles(d)));
            case ONBOARDING_LOCATIONS -> api.sendMessage(chatId, Texts.stepLocations(), Keyboards.locations(locations(d)));
            case ONBOARDING_EXPERIENCE -> api.sendMessage(chatId, Texts.stepExperience(), Keyboards.experience());
            case ONBOARDING_INTERNSHIPS -> api.sendMessage(chatId, Texts.stepInternships(), Keyboards.internships());
            case ONBOARDING_RESUME -> api.sendMessage(chatId, Texts.stepResume(), Keyboards.skipResume());
            default -> { }
        }
    }

    // ----------------------------------------------------------------------------------------- buttons

    /** Handles r:, l:, x:, i:, cv:, p:, s: and c: buttons. Returns a short toast for the tap, or null. */
    String onCallback(User user, CallbackQuery cq) {
        String data = cq.data();
        long chatId = cq.message().chat().id();
        long messageId = cq.message().messageId();
        Session session = sessions.load(user.id());
        String prefix = data.substring(0, data.indexOf(':'));
        String value = data.substring(data.indexOf(':') + 1);
        return switch (prefix) {
            case "r" -> roleTapped(user, session, value, chatId, messageId);
            case "l" -> locationTapped(user, session, value, chatId, messageId);
            case "x" -> experienceTapped(user, session, value, chatId, messageId);
            case "i" -> internshipsTapped(user, session, "y".equals(value), chatId, messageId);
            case "cv" -> skipResumeTapped(user, session, chatId, messageId);
            case "p" -> prefsTapped(user, value, chatId, messageId);
            case "s" -> profileChange(user, chatId, messageId, p -> profiles.setMinScore(user.id(), Integer.parseInt(value)));
            case "c" -> profileChange(user, chatId, messageId, p -> profiles.setDailyCap(user.id(), Integer.parseInt(value)));
            default -> STALE;
        };
    }

    private String roleTapped(User user, Session session, String value, long chatId, long messageId) {
        if (session.state() != State.ONBOARDING_ROLES && session.state() != State.EDIT_ROLES) {
            return STALE;
        }
        if (!value.equals("done")) {
            if (Labels.roleByCode(value).isEmpty()) {
                return STALE;
            }
            Session next = session.with(session.draft().toggleRole(value));
            sessions.save(user.id(), next);
            api.editMessageReplyMarkup(chatId, messageId, Keyboards.roles(roles(next.draft())));
            return null;
        }
        if (session.draft().roles().isEmpty()) {
            return "Pick at least one role.";
        }
        if (session.state() == State.ONBOARDING_ROLES) {
            Session next = session.with(State.ONBOARDING_LOCATIONS);
            sessions.save(user.id(), next);
            api.editMessageText(chatId, messageId, done(Texts.stepRoles(), Texts.roles(roles(next.draft()))), null);
            api.sendMessage(chatId, Texts.stepLocations(), Keyboards.locations(locations(next.draft())));
            return null;
        }
        Profile p = current(user);
        Profile saved = profiles.saveAnswers(user.id(), roles(session.draft()), ProfileService.locations(p),
                p.yearsExperience(), p.includeInternships());
        sessions.save(user.id(), Session.idle());
        api.editMessageText(chatId, messageId, Texts.prefs(saved), Keyboards.prefsMenu());
        sendRecentMatches(user);
        return "Saved.";
    }

    private String locationTapped(User user, Session session, String value, long chatId, long messageId) {
        if (session.state() != State.ONBOARDING_LOCATIONS && session.state() != State.EDIT_LOCATIONS) {
            return STALE;
        }
        if (!value.equals("done")) {
            if (LocationChoice.byCode(value).isEmpty()) {
                return STALE;
            }
            Session next = session.with(session.draft().toggleLocation(value));
            sessions.save(user.id(), next);
            api.editMessageReplyMarkup(chatId, messageId, Keyboards.locations(locations(next.draft())));
            return null;
        }
        if (session.draft().locations().isEmpty()) {
            return "Pick at least one location.";
        }
        if (session.state() == State.ONBOARDING_LOCATIONS) {
            Session next = session.with(State.ONBOARDING_EXPERIENCE);
            sessions.save(user.id(), next);
            api.editMessageText(chatId, messageId,
                    done(Texts.stepLocations(), Texts.locations(locations(next.draft()))), null);
            api.sendMessage(chatId, Texts.stepExperience(), Keyboards.experience());
            return null;
        }
        Profile p = current(user);
        Profile saved = profiles.saveAnswers(user.id(), p.roles(), locations(session.draft()), p.yearsExperience(),
                p.includeInternships());
        sessions.save(user.id(), Session.idle());
        api.editMessageText(chatId, messageId, Texts.prefs(saved), Keyboards.prefsMenu());
        sendRecentMatches(user);
        return "Saved.";
    }

    private String experienceTapped(User user, Session session, String code, long chatId, long messageId) {
        Optional<Labels.Experience> choice = Labels.experienceByCode(code);
        if (choice.isEmpty()) {
            return STALE;
        }
        double years = choice.get().years();
        if (session.state() == State.ONBOARDING_EXPERIENCE) {
            Session next = new Session(State.ONBOARDING_INTERNSHIPS, session.draft().withYears(years));
            sessions.save(user.id(), next);
            api.editMessageText(chatId, messageId, done(Texts.stepExperience(), choice.get().label()), null);
            api.sendMessage(chatId, Texts.stepInternships(), Keyboards.internships());
            return null;
        }
        String toast = profileChange(user, chatId, messageId, p -> profiles.saveAnswers(user.id(), p.roles(),
                ProfileService.locations(p), years, p.includeInternships()));
        if (!STALE.equals(toast)) {
            sendRecentMatches(user);
        }
        return toast;
    }

    private String internshipsTapped(User user, Session session, boolean include, long chatId, long messageId) {
        if (session.state() == State.ONBOARDING_INTERNSHIPS) {
            Draft d = session.draft().withInternships(include);
            // Save the answers now, so the resume step has a profile to add skills to.
            profiles.saveAnswers(user.id(), roles(d), locations(d), d.years() == null ? 0 : d.years(), include);
            sessions.save(user.id(), new Session(State.ONBOARDING_RESUME, d));
            api.editMessageText(chatId, messageId, done(Texts.stepInternships(), include ? "Yes" : "No"), null);
            api.sendMessage(chatId, Texts.stepResume(), Keyboards.skipResume());
            return null;
        }
        String toast = profileChange(user, chatId, messageId, p -> profiles.saveAnswers(user.id(), p.roles(),
                ProfileService.locations(p), p.yearsExperience(), include));
        if (!STALE.equals(toast)) {
            sendRecentMatches(user);
        }
        return toast;
    }

    private String skipResumeTapped(User user, Session session, long chatId, long messageId) {
        if (session.state() == State.ONBOARDING_RESUME) {
            api.editMessageReplyMarkup(chatId, messageId, null);
            finish(user, chatId);
            return null;
        }
        if (session.state() == State.AWAITING_RESUME) {
            sessions.save(user.id(), Session.idle());
            api.editMessageText(chatId, messageId, "Upload cancelled.", null);
            return null;
        }
        return STALE;
    }

    // ----------------------------------------------------------------------------------------- /prefs

    void showPrefs(User user, long chatId) {
        profiles.find(user.id()).ifPresent(p -> api.sendMessage(chatId, Texts.prefs(p), Keyboards.prefsMenu()));
    }

    private String prefsTapped(User user, String item, long chatId, long messageId) {
        if (!onboarded(user)) {
            return STALE;
        }
        Profile p = current(user);
        switch (item) {
            case "roles" -> {
                List<String> codes = p.roles().stream().map(Labels::code).toList();
                sessions.save(user.id(), new Session(State.EDIT_ROLES, new Draft(codes, List.of(), null, null)));
                api.editMessageText(chatId, messageId, "Pick your roles, then tap <b>Done</b>.", Keyboards.roles(p.roles()));
            }
            case "locs" -> {
                List<LocationChoice> current = ProfileService.locations(p);
                List<String> codes = current.stream().map(LocationChoice::code).toList();
                sessions.save(user.id(), new Session(State.EDIT_LOCATIONS, new Draft(List.of(), codes, null, null)));
                api.editMessageText(chatId, messageId, "Pick your locations, then tap <b>Done</b>.",
                        Keyboards.locations(current));
            }
            case "exp" -> api.editMessageText(chatId, messageId,
                    "How much full-time work experience do you have?", Keyboards.experience());
            case "intern" -> api.editMessageText(chatId, messageId, "Should I include internships?", Keyboards.internships());
            case "score" -> api.editMessageText(chatId, messageId, Texts.minScorePrompt(), Keyboards.minScore(p.minScore()));
            case "cap" -> api.editMessageText(chatId, messageId, Texts.dailyCapPrompt(), Keyboards.dailyCap(p.dailyCap()));
            case "back" -> api.editMessageText(chatId, messageId, Texts.prefs(p), Keyboards.prefsMenu());
            default -> {
                return STALE;
            }
        }
        return null;
    }

    private String profileChange(User user, long chatId, long messageId, java.util.function.Function<Profile, Profile> change) {
        if (!onboarded(user)) {
            return STALE;
        }
        Profile saved = change.apply(current(user));
        sessions.save(user.id(), Session.idle());
        api.editMessageText(chatId, messageId, Texts.prefs(saved), Keyboards.prefsMenu());
        return "Saved.";
    }

    // ----------------------------------------------------------------------------------------- finish

    /** Last step: alerts on, summary sent, then Block 4's "recent matches". */
    void finish(User user, long chatId) {
        users.activate(user.id(), clock.instant());
        sessions.save(user.id(), Session.idle());
        profiles.find(user.id()).ifPresent(p -> api.sendMessage(chatId, Texts.alertsOn(p), null));
        sendRecentMatches(user);
    }

    private Profile current(User user) {
        return profiles.find(user.id()).orElseThrow(() -> new IllegalStateException("onboarded user without profile"));
    }

    /** The step's question, now answered: "<question>\n✓ <answer>", with the buttons removed. */
    private static String done(String question, String answer) {
        return question.split("\n")[0] + "\n✓ " + io.github.yuvrajtiwary.earlybird.common.Html.escape(answer);
    }

    private static List<RoleCategory> roles(Draft d) {
        return d.roles().stream().flatMap(code -> Labels.roleByCode(code).stream()).toList();
    }

    private static List<LocationChoice> locations(Draft d) {
        return d.locations().stream().flatMap(code -> LocationChoice.byCode(code).stream()).toList();
    }
}
