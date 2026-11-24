package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.telegram.api.BotCommand;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Parsing of "/command args" messages, and the public command menu. */
final class Commands {

    private Commands() {}

    record Command(String name, String args) {}

    /** The "/" menu users see (setMyCommands). Admin commands are deliberately not listed. */
    static final List<BotCommand> MENU = List.of(
            new BotCommand("start", "Set up alerts"),
            new BotCommand("prefs", "Roles, locations, experience, threshold"),
            new BotCommand("skills", "See or edit your skills"),
            new BotCommand("upload", "Read skills from your resume (PDF)"),
            new BotCommand("pause", "Pause alerts"),
            new BotCommand("unpause", "Resume alerts"),
            new BotCommand("stats", "Your last 7 days"),
            new BotCommand("delete", "Erase everything about you"),
            new BotCommand("help", "All commands"),
            new BotCommand("cancel", "Stop what you're doing"));

    /** "/skills@EarlyBirdJobsBot add Kafka" → (skills, "add Kafka"); null if it isn't a command. */
    static Command parse(String text) {
        if (text == null || !text.startsWith("/")) {
            return null;
        }
        String trimmed = text.strip();
        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed : trimmed.substring(0, space);
        String args = space < 0 ? "" : trimmed.substring(space + 1).strip();
        int at = head.indexOf('@');
        String name = (at < 0 ? head.substring(1) : head.substring(1, at)).toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : new Command(name, args);
    }

    /** What "/skills ..." asks for. */
    record SkillsRequest(Action action, List<String> skills) {
        enum Action { LIST, ADD, REMOVE }
    }

    /** "", "add a, b", "remove x". Anything else is treated as a request to list (with usage shown). */
    static SkillsRequest parseSkills(String args) {
        String[] parts = args.strip().split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        List<String> skills = parts.length < 2 ? List.of()
                : Arrays.stream(parts[1].split("[,\\n;]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        if ((verb.equals("add") || verb.equals("+")) && !skills.isEmpty()) {
            return new SkillsRequest(SkillsRequest.Action.ADD, skills);
        }
        if ((verb.equals("remove") || verb.equals("rm") || verb.equals("delete") || verb.equals("-")) && !skills.isEmpty()) {
            return new SkillsRequest(SkillsRequest.Action.REMOVE, skills);
        }
        return new SkillsRequest(SkillsRequest.Action.LIST, List.of());
    }
}
