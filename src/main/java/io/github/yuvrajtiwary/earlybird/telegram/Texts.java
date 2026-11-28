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
 * <p>House style (issue #10): quiet and plain. A bold headline, short plain lines, a blank line between parts,
 * italics for asides. No emoji in the text; the alert's logo card carries the visual weight.
 */
final class Texts {

    static final String CONSENT = "<i>Your resume text, with email, phone and links removed, is sent to Google's "
            + "Gemini API to extract your skills. The file isn't stored. /delete erases everything.</i>";

    static final int STEPS = 5;
    /** A profile card shows this many skills, then "+N more", so a 40-skill resume doesn't swamp it. */
    static final int SKILLS_IN_CARD = 10;

    private Texts() {}

    // ------------------------------------------------------------------------------------ onboarding

    static String welcome(String firstName) {
        String name = firstName == null || firstName.isBlank() ? "" : ", " + escape(firstName);
        return "<b>Welcome to EarlyBird" + name + "</b>\n\n"
                + "I watch the careers pages of 130 companies and message you within minutes when a job that fits "
                + "you goes live, with your match score and the skills you're missing.\n\n"
                + "<i>I keep only your Telegram name, your preferences and the skills from your resume, never the "
                + "file. /delete erases everything, any time.</i>\n\n"
                + "Setup takes about a minute.";
    }

    /** "<b>Roles</b> · step 1 of 5". The first line is what stays once the step is answered. */
    static String step(int n, String name) {
        return "<b>" + name + "</b> · step " + n + " of " + STEPS;
    }

    static String stepRoles() {
        return step(1, "Roles") + "\nWhich kinds of roles do you want? Pick all that fit, then tap Done.";
    }

    static String stepLocations() {
        return step(2, "Locations") + "\nWhere can you work? Pick all that fit, then tap Done.";
    }

    static String stepExperience() {
        return step(3, "Experience") + "\nHow much full-time work experience do you have?";
    }

    static String stepInternships() {
        return step(4, "Internships") + "\nShould I include internships?";
    }

    static String stepResume() {
        return step(5, "Resume") + "\nSend your resume as a PDF and I'll pick out your skills, so I can score each "
                + "job and tell you what you're missing.\n\n" + CONSENT;
    }

    static String alertsOn(Profile p) {
        return "<b>You're all set. Alerts are on.</b>\n\n"
                + profileCard(p) + "\n\n"
                + "I'll message you here when a matching job goes live.\n"
                + "<i>/prefs to change anything · /help for all commands</i>";
    }

    // ------------------------------------------------------------------------------------ preferences

    static String prefs(Profile p) {
        return "<b>Your preferences</b>\n\n"
                + profileCard(p) + "\n"
                + "Instant alerts from: " + p.minScore() + "% match\n"
                + "Daily cap: " + p.dailyCap() + " alerts\n\n"
                + "<i>Tap what you'd like to change.</i>";
    }

    private static String profileCard(Profile p) {
        String skills = p.skills().isEmpty()
                ? "none yet, send /upload with your resume"
                : skillList(p.skills(), SKILLS_IN_CARD);
        return "Roles: " + roles(p.roles()) + "\n"
                + "Locations: " + locations(ProfileService.locations(p)) + "\n"
                + "Experience: " + Labels.experience(p.yearsExperience()) + "\n"
                + "Internships: " + (p.includeInternships() ? "included" : "not included") + "\n"
                + "Skills: " + skills;
    }

    static String editRoles() {
        return "<b>Roles</b>\nPick all that fit, then tap Done.";
    }

    static String editLocations() {
        return "<b>Locations</b>\nPick all that fit, then tap Done.";
    }

    static String editExperience() {
        return "<b>Experience</b>\nHow much full-time work experience do you have?";
    }

    static String editInternships() {
        return "<b>Internships</b>\nShould I include internships?";
    }

    static String minScorePrompt() {
        return "<b>Instant alert threshold</b>\n"
                + "Jobs matching at least this much reach you at once. Lower matches, from 40%, wait for the "
                + "8 PM digest.";
    }

    static String dailyCapPrompt() {
        return "<b>Daily cap</b>\nThe most instant alerts you'll get in a day. Extra matches wait for the 8 PM digest.";
    }

    // ------------------------------------------------------------------------------------ resume and skills

    static String uploadPrompt() {
        return "<b>Upload your resume</b>\nSend it as a PDF, up to 5 MB.\n\n" + CONSENT;
    }

    static String resumeRead(ResumeFacts facts, double chosenYears) {
        StringBuilder text = new StringBuilder();
        if (facts.skills().isEmpty()) {
            text.append("<b>Resume read</b>\nI couldn't find specific skills in it. Add them with "
                    + "<code>/skills add Java, SQL</code>.");
        } else {
            text.append("<b>Resume read: ").append(facts.skills().size())
                    .append(facts.skills().size() == 1 ? " skill" : " skills").append("</b>\n")
                    .append(skillList(facts.skills(), Integer.MAX_VALUE));
        }
        StringBuilder details = new StringBuilder();
        if (facts.graduationYear() != null) {
            details.append("Graduation: ").append(facts.graduationYear()).append("\n");
        }
        if (facts.yearsExperience() != null) {
            details.append("Full-time experience on the resume: about ")
                    .append(formatYears(facts.yearsExperience())).append("\n");
            if (Math.abs(facts.yearsExperience() - chosenYears) >= 1.0) {
                details.append("<i>You picked \"").append(Labels.experience(chosenYears))
                        .append("\". Change it in /prefs if that's not right.</i>\n");
            }
        }
        if (!details.isEmpty()) {
            text.append("\n\n").append(details.toString().strip());
        }
        return text.append("\n\n<i>Fix anything with</i> <code>/skills add Kafka</code> <i>or</i> "
                + "<code>/skills remove PHP</code>").toString();
    }

    static String sameResume(Profile p) {
        return "<b>Same resume as last time</b>, so nothing changed.\n"
                + (p.skills().isEmpty() ? "No skills saved." : "Skills: " + skillList(p.skills(), Integer.MAX_VALUE));
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

    static String uploadCancelled() {
        return "Upload cancelled.";
    }

    static String skills(List<String> skills) {
        String edit = "<i>Edit with</i> <code>/skills add Kafka, Docker</code> <i>or</i> <code>/skills remove PHP</code>";
        if (skills.isEmpty()) {
            return "<b>Your skills</b>\nNone saved yet. Send /upload and I'll read them from your resume.\n\n" + edit;
        }
        return "<b>Your skills (" + skills.size() + ")</b>\n" + skillList(skills, Integer.MAX_VALUE) + "\n\n" + edit;
    }

    // ------------------------------------------------------------------------------------ stats and help

    static String stats(UserStats s) {
        if (s.total() == 0) {
            return "<b>Your last 7 days</b>\nNo alerts yet. Jobs that match you will show up here.";
        }
        StringBuilder text = new StringBuilder("<b>Your last 7 days</b>\n")
                .append(s.total()).append(s.total() == 1 ? " alert: " : " alerts: ")
                .append(s.instant()).append(" instant, ").append(s.digest()).append(" in digests");
        int rated = s.relevant() + s.notRelevant();
        if (rated > 0) {
            text.append("\n").append(s.relevant()).append(" of ").append(rated).append(" rated relevant");
        }
        if (s.mostMissingSkill() != null) {
            text.append("\nMost-missed skill: ").append(escape(s.mostMissingSkill()));
        }
        return text.toString();
    }

    static String help() {
        return """
                <b>Commands</b>
                /prefs – roles, locations, experience, threshold
                /skills – see or edit your skills
                /upload – read skills from your resume
                /pause – stop alerts for now
                /unpause – start them again
                /stats – your last 7 days
                /delete – erase everything about you
                /cancel – stop what you're doing""";
    }

    // ------------------------------------------------------------------------------------ account

    static String confirmDelete() {
        return "<b>Delete everything?</b>\n"
                + "This permanently erases your profile, preferences, skills and alert history. There's no undo.";
    }

    static String deleted() {
        return "<b>Done.</b> Everything EarlyBird stored about you has been deleted.\n"
                + "<i>Send /start if you ever want alerts again.</i>";
    }

    static String keptEverything() {
        return "Kept everything. Nothing was deleted.";
    }

    /** Also shown as a callback toast, which is plain text: no HTML here. */
    static String needStart() {
        return "Send /start to set up EarlyBird.";
    }

    static String finishSetupFirst() {
        return "Let's finish setting up first. Send /start to pick up where you left off.";
    }

    static String paused() {
        return "<b>Alerts paused.</b>\nI'll stay quiet until you send /unpause.";
    }

    static String unpaused() {
        return "<b>Alerts are back on.</b>\nI'll message you when a matching job goes live.";
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

    /** Also shown as a callback toast, which is plain text: no HTML here. */
    static String slowDown() {
        return "You're going quickly. Give me a few seconds.";
    }

    // ------------------------------------------------------------------------------------ pieces

    static String roles(Collection<RoleCategory> roles) {
        return roles.isEmpty() ? "none" : String.join(", ", roles.stream().map(Labels::role).toList());
    }

    static String locations(Collection<LocationChoice> locations) {
        return locations.isEmpty() ? "none" : String.join(", ", locations.stream().map(LocationChoice::label).toList());
    }

    /** "Java, Spring Boot, SQL", escaped; past {@code max}, "… +N more". */
    static String skillList(List<String> skills, int max) {
        String shown = escape(String.join(", ", skills.size() > max ? skills.subList(0, max) : skills));
        return skills.size() > max ? shown + " +" + (skills.size() - max) + " more" : shown;
    }

    static String formatYears(double years) {
        return (years == Math.floor(years) ? String.valueOf((long) years) : String.valueOf(years))
                + (years == 1.0 ? " year" : " years");
    }
}
