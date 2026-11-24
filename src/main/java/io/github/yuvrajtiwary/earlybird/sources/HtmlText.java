package io.github.yuvrajtiwary.earlybird.sources;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Job descriptions arrive as HTML. Jsoup parses it the way a browser would (broken tags, entities, nested
 * lists), and the result keeps paragraph breaks and list bullets, which helps tagging. No HTML is ever
 * rendered or stored.
 */
public final class HtmlText {

    private HtmlText() {}

    /**
     * HTML to readable plain text.
     *
     * @param dropSelectors CSS selectors of elements to leave out, e.g. company boilerplate blocks
     */
    public static String toPlainText(String html, String... dropSelectors) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Element body = Jsoup.parseBodyFragment(html).body();
        for (String selector : dropSelectors) {
            body.select(selector).remove();
        }
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(text.text());
                } else if (node instanceof Element element) {
                    if (element.nameIs("br")) {
                        out.append('\n');
                    } else if (element.nameIs("li")) {
                        out.append("\n- ");
                    } else if (element.isBlock()) {
                        out.append('\n');
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && element.isBlock() && !element.nameIs("li")) {
                    out.append('\n');
                }
            }
        }, body);
        return normalize(out.toString());
    }

    /** Greenhouse sends the HTML itself HTML-escaped ({@code &lt;p&gt;...}); unescape once, then convert. */
    public static String fromEscapedHtml(String escapedHtml, String... dropSelectors) {
        return escapedHtml == null ? "" : toPlainText(Parser.unescapeEntities(escapedHtml, false), dropSelectors);
    }

    /** Collapses runs of spaces, trims each line and drops repeated blank lines. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        boolean previousBlank = true;
        for (String line : text.replace(' ', ' ').split("\\R")) {
            String clean = line.replaceAll("[ \\t\\x0B\\f]+", " ").strip();
            if (clean.isEmpty() || clean.equals("-")) {
                if (!previousBlank) {
                    lines.add("");
                }
                previousBlank = true;
            } else {
                lines.add(clean);
                previousBlank = false;
            }
        }
        return String.join("\n", lines).strip();
    }

    /** Cuts to at most {@code max} characters, preferring a word boundary. */
    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max);
        return text.substring(0, cut > max * 0.8 ? cut : max).strip();
    }
}
