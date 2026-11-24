package io.github.yuvrajtiwary.earlybird.common;

/**
 * Escaping for Telegram's HTML parse mode, which understands four named entities: {@code &lt; &gt; &amp; &quot;}.
 * Every piece of text we didn't write (job titles, company names, user names, skills) goes through this, so
 * it can't inject tags or break the message.
 */
public final class Html {

    private Html() {}

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
