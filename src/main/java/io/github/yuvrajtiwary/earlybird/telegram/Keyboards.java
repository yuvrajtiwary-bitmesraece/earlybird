package io.github.yuvrajtiwary.earlybird.telegram;

import static io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardButton.callback;

import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.profiles.LocationChoice;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardButton;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardMarkup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The inline keyboards. Callback data (sent back when a button is tapped) must fit in 64 bytes, so it is a
 * short prefix plus a code, e.g. {@code r:backend} or {@code l:blr}. A test checks every button's size.
 *
 * <ul>
 *   <li>{@code r:} toggle role, {@code l:} toggle location ({@code :done} to finish)
 *   <li>{@code x:} experience, {@code i:} internships, {@code cv:skip} skip the resume
 *   <li>{@code p:} preferences menu, {@code s:} min score, {@code c:} daily cap, {@code del:} delete confirm
 * </ul>
 */
final class Keyboards {

    static final String CHECK = "✅ ";

    private Keyboards() {}

    static InlineKeyboardMarkup roles(Collection<RoleCategory> selected) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (RoleCategory role : Labels.ROLES) {
            buttons.add(callback((selected.contains(role) ? CHECK : "") + Labels.role(role), "r:" + Labels.code(role)));
        }
        return withDone(twoPerRow(buttons), "r:done");
    }

    static InlineKeyboardMarkup locations(Collection<LocationChoice> selected) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (LocationChoice location : LocationChoice.values()) {
            buttons.add(callback((selected.contains(location) ? CHECK : "") + location.label(), "l:" + location.code()));
        }
        return withDone(twoPerRow(buttons), "l:done");
    }

    static InlineKeyboardMarkup experience() {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Labels.Experience e : Labels.EXPERIENCE) {
            buttons.add(callback(e.label(), "x:" + e.code()));
        }
        return new InlineKeyboardMarkup(twoPerRow(buttons));
    }

    static InlineKeyboardMarkup internships() {
        return new InlineKeyboardMarkup(List.of(List.of(
                callback("✅ Yes, include them", "i:y"), callback("Full-time only", "i:n"))));
    }

    static InlineKeyboardMarkup skipResume() {
        return new InlineKeyboardMarkup(List.of(List.of(callback("Skip for now ⏭", "cv:skip"))));
    }

    static InlineKeyboardMarkup cancelUpload() {
        return new InlineKeyboardMarkup(List.of(List.of(callback("Cancel", "cv:skip"))));
    }

    static InlineKeyboardMarkup prefsMenu() {
        return new InlineKeyboardMarkup(List.of(
                List.of(callback("💼 Roles", "p:roles"), callback("📍 Locations", "p:locs")),
                List.of(callback("🎓 Experience", "p:exp"), callback("🌱 Internships", "p:intern")),
                List.of(callback("🎯 Alert threshold", "p:score"), callback("📬 Daily cap", "p:cap"))));
    }

    static InlineKeyboardMarkup minScore(int current) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (int score : List.of(40, 50, 55, 60, 70, 80)) {
            buttons.add(callback((score == current ? CHECK : "") + score, "s:" + score));
        }
        return withBack(List.of(buttons.subList(0, 3), buttons.subList(3, 6)));
    }

    static InlineKeyboardMarkup dailyCap(int current) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (int cap : List.of(3, 5, 10, 20)) {
            buttons.add(callback((cap == current ? CHECK : "") + cap, "c:" + cap));
        }
        return withBack(List.of(buttons));
    }

    static InlineKeyboardMarkup confirmDelete() {
        return new InlineKeyboardMarkup(List.of(List.of(
                callback("🗑 Yes, delete everything", "del:yes"), callback("Keep my data", "del:no"))));
    }

    /** Every keyboard the bot can show; the callback-size test walks these. */
    static List<InlineKeyboardMarkup> all() {
        return List.of(roles(Labels.ROLES), locations(List.of(LocationChoice.values())), experience(), internships(),
                skipResume(), cancelUpload(), prefsMenu(), minScore(55), dailyCap(10), confirmDelete());
    }

    private static List<List<InlineKeyboardButton>> twoPerRow(List<InlineKeyboardButton> buttons) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 2) {
            rows.add(buttons.subList(i, Math.min(i + 2, buttons.size())));
        }
        return rows;
    }

    private static InlineKeyboardMarkup withDone(List<List<InlineKeyboardButton>> rows, String doneData) {
        List<List<InlineKeyboardButton>> all = new ArrayList<>(rows);
        all.add(List.of(callback("Done ➜", doneData)));
        return new InlineKeyboardMarkup(all);
    }

    private static InlineKeyboardMarkup withBack(List<List<InlineKeyboardButton>> rows) {
        List<List<InlineKeyboardButton>> all = new ArrayList<>(rows);
        all.add(List.of(callback("« Back", "p:back")));
        return new InlineKeyboardMarkup(all);
    }
}
