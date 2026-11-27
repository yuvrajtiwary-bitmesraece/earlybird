package io.github.yuvrajtiwary.earlybird.telegram;

import static io.github.yuvrajtiwary.earlybird.common.Html.escape;

import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository.UserStats;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.profiles.LocationChoice;
import io.github.yuvrajtiwary.earlybird.profiles.ProfileService;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeAnalyzer.ResumeException;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeFacts;
import java.util.Collection;
import java.util.List;

/**
 * Every message the bot sends, in one place, as Telegram HTML. Anything not written by us (names, skills)
 * goes through {@link io.github.yuvrajtiwary.earlybird.common.Html#escape}.
 *
 * <p>House style (issue #10): a bold headline with one emoji, a blank line between sections, lists joined with
 * " · ", longer content in a {@code <blockquote>}, and asides in italics. At most one emoji per line, so messages
 * read as designed rather than noisy.
 */
final class Texts {

    static final String CONSENT = "<i>🔒 Your resume text, with email, phone and links removed, is sent to Google's "
            + "Gemini API to extract your skills. The file isn't stored. /delete erases everything.</i>";

    static final int STEPS = 5;
    /** A profile card shows this many skills, then "+N more", so a 40-skill resume doesn't swamp it. */
    static final int SKILLS_IN_CARD = 12;

    private Texts() {}

    // ------------------------------------------------------------------------------------ onboarding

    static String welcome(String firstName) {
        String name = firstName == null || firstName.isBlank() ? "" : ", " + escape(firstName);
        return "🐦 <b>Welcome to EarlyBird" + name + "!</b>\n\n"
                + "I watch the careers pages of <b>130 companies</b> and tell you the moment a job that fits you "
                + "goes live.\n\n"
                + "<b>What you'll get</b>\n"
                + "⚡ Alerts within minutes of a job being posted\n"
                + "🎯 A match score for every job\n"
                + "📚 The skills you're missing, so you know what to learn\n"
                + "🌙 An evening digest of near-misses\n\n"
                + "<i>🔒 I keep only your Telegram name, your preferences and the skills from your resume, never the "
                + "file itself. /delete erases everything, any time.</i>\n\n"
                + "Let's set you up. It takes about a minute 👇";
    }

    /** "💼 Step 1 of 5 · Roles" over "●○○○○". The first line is what stays once the step is answered. */
    static String step(int n, String emoji, String name) {
        return emoji + " <b>Step " + n + " of " + STEPS + " · " + name + "</b>\n" + "●".repeat(n) + "○".repeat(STEPS - n);
    }

    static String stepRoles() {
        return step(1, "💼", "Roles") + "\n\nWhich kinds of roles do you want?\n<i>Pick all that fit, then tap Done.</i>";
    }

    static String stepLocations() {
        return step(2, "📍", "Locations") + "\n\nWhere can you work?\n<i>Pick all that fit, then tap Done.</i>";
    }

    static String stepExperience() {
        return step(3, "🎓", "Experience") + "\n\nHow much full-time work experience do you have?";
    }

    static String stepInternships() {
        return step(4, "🌱", "Internships") + "\n\nShould I include internships?";
    }

    static String stepResume() {
        return step(5, "📄", "Resume") + "\n\nSend your resume as a <b>PDF</b> and I'll pick out your skills, so I "
                + "can score each job and tell you what you're missing.\n\n" + CONSENT;
    }

    static String alertsOn(Profile p) {
        return "✅ <b>You're all set. Alerts are on!</b>\n\n"
                + "<blockquote>" + profileCard(p) + "</blockquote>\n\n"
                + "I'll message you here the moment a matching job goes live.\n\n"
                + "⚙️ /prefs  ·  🧰 /skills  ·  ❓ /help";
    }

    // ------------------------------------------------------------------------------------ preferences

    static String prefs(Profile p) {
        return "⚙️ <b>Your preferences</b>\n\n"
                + "<blockquote>" + profileCard(p) + "\n"
                + "🎯 <b>Instant alerts from:</b> " + p.minScore() + "/100\n"
                + "📬 <b>Daily cap:</b> " + p.dailyCap() + " alerts</blockquote>\n\n"
                + "Tap what you'd like to change 👇";
    }

    private static String profileCard(Profile p) {
        String skills = p.skills().isEmpty()
                ? "<i>none yet. Send /upload with your resume for match scores.</i>"
                : skillList(p.skills(), SKILLS_IN_CARD);
        return "💼 <b>Roles:</b> " + roles(p.roles()) + "\n"
                + "📍 <b>Locations:</b> " + locations(ProfileService.locations(p)) + "\n"
                + "🎓 <b>Experience:</b> " + Labels.experience(p.yearsExperience()) + "\n"
                + "🌱 <b>Internships:</b> " + (p.includeInternships() ? "included" : "not included") + "\n"
                + "🧰 <b>Skills:</b> " + skills;
    }

    static String editRoles() {
        return "💼 <b>Roles</b>\n\n<i>Pick all that fit, then tap Done.</i>";
    }

    static String editLocations() {
        return "📍 <b>Locations</b>\n\n<i>Pick all that fit, then tap Done.</i>";
    }

    static String editExperience() {
        return "🎓 <b>Experience</b>\n\nHow much full-time work experience do you have?";
    }

    static String editInternships() {
        return "🌱 <b>Internships</b>\n\nShould I include internships?";
    }

    static String minScorePrompt() {
        return "🎯 <b>Instant alert threshold</b>\n\n"
                + "Jobs scoring at least this much (out of 100) reach you <b>at once</b>.\n"
                + "<i>Lower matches, from 40, wait for the 🌙 8 PM digest.</i>";
    }

    static String dailyCapPrompt() {
        return "📬 <b>Daily cap</b>\n\n"
                + "The most instant alerts you'll get in a day.\n"
                + "<i>Extra matches wait for the 🌙 8 PM digest.</i>";
    }

    // ------------------------------------------------------------------------------------ resume and skills

    static String uploadPrompt() {
        return "📄 <b>Upload your resume</b>\n\nSend it as a <b>PDF</b> (up to 5 MB).\n\n" + CONSENT;
    }

    static String resumeRead(ResumeFacts facts, double chosenYears) {
        StringBuilder text = new StringBuilder();
        if (facts.skills().isEmpty()) {
            text.append("📄 <b>Resume read</b>\n\nI couldn't find specific skills in it. Add them with "
                    + "<code>/skills add Java, SQL</code>.");
        } else {
            text.append("📄 <b>Resume read!</b> I found <b>").append(facts.skills().size())
                    .append(facts.skills().size() == 1 ? " skill" : " skills").append("</b>\n\n")
                    .append("<blockquote>").append(skillList(facts.skills(), Integer.MAX_VALUE)).append("</blockquote>");
        }
        StringBuilder details = new StringBuilder();
        if (facts.graduationYear() != null) {
            details.append("🎓 <b>Graduation:</b> ").append(facts.graduationYear()).append("\n");
        }
        if (facts.yearsExperience() != null) {
            details.append("💼 <b>Full-time experience on the resume:</b> about ")
                    .append(formatYears(facts.yearsExperience())).append("\n");
            if (Math.abs(facts.yearsExperience() - chosenYears) >= 1.0) {
                details.append("<i>You picked \"").append(Labels.experience(chosenYears))
                        .append("\". Change it in /prefs if that's not right.</i>\n");
            }
        }
        if (!details.isEmpty()) {
            text.append("\n\n").append(details.toString().strip());
        }
        return text.append("\n\n✏️ <b>Fix anything</b>\n<code>/skills add Kafka</code>  ·  <code>/skills remove PHP</code>")
                .toString();
    }

    static String sameResume(Profile p) {
        return "📄 <b>Same resume as last time</b>, so nothing changed.\n\n"
                + (p.skills().isEmpty() ? "<i>No skills saved.</i>"
                        : "<blockquote>" + skillList(p.skills(), Integer.MAX_VALUE) + "</blockquote>");
    }

    static String resumeProblem(ResumeException.Reason reason) {
        return "⚠️ " + switch (reason) {
            case NOT_A_PDF -> "That doesn't look like a readable PDF. Try exporting your resume as a PDF again.";
            case PASSWORD_PROTECTED -> "That PDF is password-protected. Please send a version without a password.";
            case NO_TEXT -> "I couldn't find any text in that PDF; it may be a scanned image. Please send a PDF "
                    + "exported from Word or Google Docs.";
            case TOO_SLOW -> "That PDF took too long to read. Please try a smaller file.";
            case AI_BUSY -> "The AI service I use is busy right now. Please send the file again in a few minutes.";
            case AI_BUDGET_USED_UP -> "I've reached today's limit for reading resumes. Please try again in a few hours.";
        };
    }

    static String uploadCancelled() {
        return "↩️ Upload cancelled.";
    }

    static String skills(List<String> skills) {
        String edit = "➕ <code>/skills add Kafka, Docker</code>\n➖ <code>/skills remove PHP</code>";
        if (skills.isEmpty()) {
            return "🧰 <b>Your skills</b>\n\n<i>None saved yet.</i>\n\n" + edit + "\n📄 Or send /upload and I'll read "
                    + "them from your resume.";
        }
        return "🧰 <b>Your skills</b> · " + skills.size() + "\n\n"
                + "<blockquote>" + skillList(skills, Integer.MAX_VALUE) + "</blockquote>\n\n" + edit;
    }

    // ------------------------------------------------------------------------------------ stats and help

    static String stats(UserStats s) {
        if (s.total() == 0) {
            return "📊 <b>Your last 7 days</b>\n\n<i>No alerts yet. Jobs that match you will show up here.</i>";
        }
        StringBuilder text = new StringBuilder("📊 <b>Your last 7 days</b>\n\n")
                .append("📬 <b>").append(s.total()).append(s.total() == 1 ? " alert" : " alerts").append("</b>\n")
                .append("⚡ ").append(s.instant()).append(" instant  ·  🌙 ").append(s.digest()).append(" in digests");
        int rated = s.relevant() + s.notRelevant();
        if (rated > 0) {
            text.append("\n\n👍 <b>Relevance</b>\n").append(bar(s.relevant(), rated)).append("  ")
                    .append(s.relevant()).append(" of ").append(rated).append(" rated relevant");
        }
        if (s.mostMissingSkill() != null) {
            text.append("\n\n📚 <b>Most-missed skill:</b> ").append(escape(s.mostMissingSkill()))
                    .append("\n<i>Learning it would open up more of your matches.</i>");
        }
        return text.toString();
    }

    /** Ten cells filled in proportion: (8, 10) → ▰▰▰▰▰▰▰▰▱▱. */
    static String bar(int part, int whole) {
        int filled = whole <= 0 ? 0 : Math.clamp(Math.round(10f * part / whole), 0, 10);
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }

    static String help() {
        return """
                🐦 <b>EarlyBird commands</b>

                ⚙️ <b>Setup</b>
                /prefs – roles, locations, experience, threshold, cap
                /skills – see or edit your skills
                /upload – read skills from your resume (PDF)

                🔔 <b>Alerts</b>
                /pause – stop alerts for now
                /unpause – start them again
                /stats – your last 7 days

                🔒 <b>Your data</b>
                /delete – erase everything about you
                /cancel – stop what you're doing""";
    }

    // ------------------------------------------------------------------------------------ account

    static String confirmDelete() {
        return "⚠️ <b>Delete everything?</b>\n\n"
                + "This permanently erases your profile, preferences, skills and alert history.\n"
                + "<i>There's no undo.</i>";
    }

    static String deleted() {
        return "🗑 <b>All gone.</b>\n\nEverything EarlyBird stored about you has been deleted.\n"
                + "<i>Changed your mind? Send /start any time.</i>";
    }

    static String keptEverything() {
        return "👍 <b>Kept everything.</b> Nothing was deleted.";
    }

    /** Also shown as a callback toast, which is plain text: no HTML here. */
    static String needStart() {
        return "👋 Send /start to set up EarlyBird.";
    }

    static String finishSetupFirst() {
        return "🧭 Let's finish setting up first. Send /start to pick up where you left off.";
    }

    static String paused() {
        return "⏸ <b>Alerts paused.</b>\n\nI'll stay quiet until you send /unpause.";
    }

    static String unpaused() {
        return "▶️ <b>Alerts are back on.</b>\n\nI'll message you the moment a matching job goes live.";
    }

    static String unknownCommand() {
        return "🤔 I don't know that one. /help lists everything I can do.";
    }

    static String notACommand() {
        return "💬 I work with commands and buttons. /help lists them.";
    }

    static String sendPdfFirst() {
        return "📄 To upload a resume, send /upload first.";
    }

    /** Also shown as a callback toast, which is plain text: no HTML here. */
    static String slowDown() {
        return "⏳ You're going quickly. Give me a few seconds.";
    }

    // ------------------------------------------------------------------------------------ pieces

    static String roles(Collection<RoleCategory> roles) {
        return roles.isEmpty() ? "none" : String.join(", ", roles.stream().map(Labels::role).toList());
    }

    static String locations(Collection<LocationChoice> locations) {
        return locations.isEmpty() ? "none" : String.join(", ", locations.stream().map(LocationChoice::label).toList());
    }

    /** "Java · Spring Boot · SQL", escaped; past {@code max}, "… +N more". */
    static String skillList(List<String> skills, int max) {
        String shown = escape(String.join(" · ", skills.size() > max ? skills.subList(0, max) : skills));
        return skills.size() > max ? shown + " <i>+" + (skills.size() - max) + " more</i>" : shown;
    }

    static String formatYears(double years) {
        return (years == Math.floor(years) ? String.valueOf((long) years) : String.valueOf(years))
                + (years == 1.0 ? " year" : " years");
    }
}
