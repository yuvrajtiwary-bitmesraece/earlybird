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
 */
final class Texts {

    static final String CONSENT = "<i>Your resume text, with email, phone and links removed, is sent to Google's "
            + "Gemini API to extract your skills. The file isn't stored. /delete erases everything.</i>";

    private Texts() {}

    static String welcome(String firstName) {
        String name = firstName == null || firstName.isBlank() ? "" : ", " + escape(firstName);
        return "🐦 <b>Welcome to EarlyBird" + name + "!</b>\n\n"
                + "I watch the careers pages of 130 companies and message you within minutes when a job that "
                + "fits you goes live, with your match score and the skills you're missing.\n\n"
                + "🔒 <b>Privacy:</b> I keep your Telegram name, your preferences and the skills from your resume "
                + "(never the file itself). /delete erases everything, any time.\n\n"
                + "Let's set you up. It takes about a minute.";
    }

    static String stepRoles() {
        return "<b>Step 1 of 5 · Roles</b>\nWhich kinds of roles do you want? Pick all that fit, then tap <b>Done</b>.";
    }

    static String stepLocations() {
        return "<b>Step 2 of 5 · Locations</b>\nWhere can you work? Pick all that fit, then tap <b>Done</b>.";
    }

    static String stepExperience() {
        return "<b>Step 3 of 5 · Experience</b>\nHow much full-time work experience do you have?";
    }

    static String stepInternships() {
        return "<b>Step 4 of 5 · Internships</b>\nShould I include internships?";
    }

    static String stepResume() {
        return "<b>Step 5 of 5 · Resume</b>\nSend your resume as a <b>PDF</b> and I'll pick out your skills, so I "
                + "can score each job and tell you what you're missing.\n\n" + CONSENT;
    }

    static String uploadPrompt() {
        return "📄 Send your resume as a <b>PDF</b> (up to 5 MB).\n\n" + CONSENT;
    }

    static String alertsOn(Profile p) {
        return "✅ <b>Alerts are on.</b>\n\n" + profileLines(p)
                + "\nWhen a matching job goes live, I'll message you here.\n"
                + "Change anything with /prefs or /skills · /help lists all commands.";
    }

    static String prefs(Profile p) {
        return "⚙️ <b>Your preferences</b>\n\n" + profileLines(p)
                + "<b>Instant alert threshold:</b> " + p.minScore() + "/100\n"
                + "<b>Daily cap:</b> " + p.dailyCap() + " instant alerts\n\n"
                + "Tap what you'd like to change:";
    }

    private static String profileLines(Profile p) {
        String skills = p.skills().isEmpty()
                ? "none yet. Send /upload with your resume for match scores."
                : escape(String.join(", ", p.skills()));
        return "<b>Roles:</b> " + roles(p.roles()) + "\n"
                + "<b>Locations:</b> " + locations(ProfileService.locations(p)) + "\n"
                + "<b>Experience:</b> " + Labels.experience(p.yearsExperience()) + "\n"
                + "<b>Internships:</b> " + (p.includeInternships() ? "included" : "not included") + "\n"
                + "<b>Skills:</b> " + skills + "\n";
    }

    static String minScorePrompt() {
        return "<b>Instant alert threshold</b>\nJobs scoring at least this much (out of 100) are sent at once; "
                + "lower matches (from 40) wait for the 20:00 digest.";
    }

    static String dailyCapPrompt() {
        return "<b>Daily cap</b>\nThe most instant alerts per day. Extra matches go to the 20:00 digest.";
    }

    static String resumeRead(ResumeFacts facts, double chosenYears) {
        StringBuilder text = new StringBuilder();
        if (facts.skills().isEmpty()) {
            text.append("📄 I read your resume but couldn't find specific skills. Add them with "
                    + "<code>/skills add Java, SQL</code>.");
        } else {
            text.append("📄 <b>Got it.</b> I found ").append(facts.skills().size()).append(" skills:\n")
                    .append(escape(String.join(", ", facts.skills())));
        }
        text.append("\n\n");
        if (facts.graduationYear() != null) {
            text.append("<b>Graduation:</b> ").append(facts.graduationYear()).append("\n");
        }
        if (facts.yearsExperience() != null) {
            text.append("<b>Full-time experience on the resume:</b> about ")
                    .append(formatYears(facts.yearsExperience())).append("\n");
            if (Math.abs(facts.yearsExperience() - chosenYears) >= 1.0) {
                text.append("You picked <i>").append(Labels.experience(chosenYears))
                        .append("</i>; change it in /prefs if that's not right.\n");
            }
        }
        text.append("\nFix anything with <code>/skills add Kafka</code> or <code>/skills remove PHP</code>.");
        return text.toString();
    }

    static String sameResume(Profile p) {
        return "📄 That's the same resume as last time, so nothing changed. Your skills: "
                + (p.skills().isEmpty() ? "none" : escape(String.join(", ", p.skills())));
    }

    static String resumeProblem(ResumeException.Reason reason) {
        return switch (reason) {
            case NOT_A_PDF -> "That doesn't look like a readable PDF. Try exporting your resume as a PDF again.";
            case PASSWORD_PROTECTED -> "That PDF is password-protected. Please send a version without a password.";
            case NO_TEXT -> "I couldn't find any text in that PDF; it may be a scanned image. Please send a PDF "
                    + "exported from Word or Google Docs.";
            case TOO_SLOW -> "That PDF took too long to read. Please try a smaller file.";
            case AI_BUSY -> "The AI service I use is busy right now. Please send the file again in a few minutes.";
            case AI_BUDGET_USED_UP -> "I've reached today's limit for reading resumes. Please try again in a few hours.";
        };
    }

    static String skills(List<String> skills) {
        if (skills.isEmpty()) {
            return "🧰 You have no skills saved yet.\n\nAdd some: <code>/skills add Java, Spring Boot, SQL</code>\n"
                    + "Or send /upload and I'll read them from your resume.";
        }
        return "🧰 <b>Your skills (" + skills.size() + "):</b>\n" + escape(String.join(", ", skills)) + "\n\n"
                + "<code>/skills add Kafka, Docker</code> · <code>/skills remove PHP</code>";
    }

    static String stats(UserStats s) {
        if (s.total() == 0) {
            return "📊 <b>Your last 7 days</b>\nNo alerts yet. Jobs that match you will show up here.";
        }
        StringBuilder text = new StringBuilder("📊 <b>Your last 7 days</b>\n")
                .append("<b>Alerts:</b> ").append(s.total()).append(" (").append(s.instant()).append(" instant, ")
                .append(s.digest()).append(" in digests)\n");
        int rated = s.relevant() + s.notRelevant();
        if (rated > 0) {
            text.append("<b>Alert relevance:</b> ").append(s.relevant()).append(" of ").append(rated)
                    .append(" rated 👍\n");
        }
        if (s.mostMissingSkill() != null) {
            text.append("<b>Skill you're most often missing:</b> ").append(escape(s.mostMissingSkill())).append("\n");
        }
        return text.toString();
    }

    static String help() {
        return """
                🐦 <b>EarlyBird commands</b>
                /prefs – roles, locations, experience, alert threshold, daily cap
                /skills – see or edit your skills
                /upload – read skills from your resume (PDF)
                /pause – stop alerts for now · /unpause – start again
                /stats – your last 7 days
                /delete – erase everything about you
                /cancel – stop what you're doing""";
    }

    static String confirmDelete() {
        return "⚠️ This permanently deletes your profile, preferences, skills and alert history. There's no undo.";
    }

    static String deleted() {
        return "🗑 Done. Everything EarlyBird stored about you has been deleted. If you ever want alerts again, "
                + "send /start.";
    }

    static String needStart() {
        return "Send /start to set up EarlyBird.";
    }

    static String finishSetupFirst() {
        return "Let's finish setting up first. Send /start to continue where you left off.";
    }

    static String paused() {
        return "⏸ Alerts paused. Send /unpause to turn them back on.";
    }

    static String unpaused() {
        return "▶️ Alerts are back on.";
    }

    static String unknownCommand() {
        return "I don't know that one. /help lists what I can do.";
    }

    static String notACommand() {
        return "I work with commands and buttons. /help lists them.";
    }

    static String sendPdfFirst() {
        return "To upload a resume, send /upload first.";
    }

    static String slowDown() {
        return "You're going quickly. Give me a few seconds.";
    }

    static String roles(Collection<RoleCategory> roles) {
        return roles.isEmpty() ? "none" : String.join(", ", roles.stream().map(Labels::role).toList());
    }

    static String locations(Collection<LocationChoice> locations) {
        return locations.isEmpty() ? "none" : String.join(", ", locations.stream().map(LocationChoice::label).toList());
    }

    static String formatYears(double years) {
        return (years == Math.floor(years) ? String.valueOf((long) years) : String.valueOf(years))
                + (years == 1.0 ? " year" : " years");
    }
}
