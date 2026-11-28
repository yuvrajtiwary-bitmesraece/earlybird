package io.github.yuvrajtiwary.earlybird.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a Telegram HTML message the way Telegram does: only its supported tags, each closed in order. Telegram
 * rejects the whole message ("can't parse entities") over a single mistake, so tests run every message through this.
 */
public final class HtmlBalance {

    private static final Set<String> TAGS = Set.of("b", "i", "u", "s", "code", "pre", "a", "blockquote", "tg-spoiler");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-z-]+)(\\s[^>]*)?>");

    private HtmlBalance() {}

    /** @return the first problem found, or "" if the message is well formed */
    public static String unbalanced(String html) {
        Deque<String> open = new ArrayDeque<>();
        Matcher m = TAG.matcher(html);
        while (m.find()) {
            String name = m.group(2);
            if (!TAGS.contains(name)) {
                return "unsupported tag <" + name + ">";
            }
            if (m.group(1).isEmpty()) {
                open.push(name);
            } else if (open.isEmpty() || !open.pop().equals(name)) {
                return "unexpected </" + name + ">";
            }
        }
        if (!open.isEmpty()) {
            return "unclosed <" + open.peek() + ">";
        }
        // Anything left that looks like a tag but isn't one would be read as broken markup.
        String stripped = TAG.matcher(html).replaceAll("");
        return stripped.contains("<") || stripped.contains(">") ? "stray < or >" : "";
    }
}
