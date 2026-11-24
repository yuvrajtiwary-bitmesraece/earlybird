package io.github.yuvrajtiwary.earlybird.sources;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.support.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HtmlTextTest {

    @Test
    void keepsParagraphsAndListItems() {
        String html = "<p>Hello <b>world</b></p><ul><li>Java</li><li>Kafka</li></ul><p>Bye</p>";

        assertThat(HtmlText.toPlainText(html)).isEqualTo("Hello world\n\n- Java\n- Kafka\n\nBye");
    }

    @Test
    void lineBreaksAndEntities() {
        assertThat(HtmlText.toPlainText("a<br>b")).isEqualTo("a\nb");
        assertThat(HtmlText.toPlainText("Tom&nbsp;&amp;&nbsp;Jerry")).isEqualTo("Tom & Jerry");
    }

    @Test
    void scriptsAndStylesAreNotText() {
        assertThat(HtmlText.toPlainText("<p>Hi</p><script>steal()</script><style>p{}</style>")).isEqualTo("Hi");
    }

    @Test
    void greenhouseSendsEscapedHtmlWhichIsUnescapedExactlyOnce() {
        // "&amp;amp;" is a literal "&amp;" in the HTML, which displays as "&".
        assertThat(HtmlText.fromEscapedHtml("&lt;p&gt;Build &amp;amp; ship&lt;/p&gt;")).isEqualTo("Build & ship");
    }

    @Test
    void aRealGreenhouseDescriptionBecomesCleanText() {
        var detail = JsonMapper.builder().build().readTree(Fixtures.read("greenhouse/hackerrank-job-detail.json"));

        String text = HtmlText.fromEscapedHtml(detail.path("content").asString());

        assertThat(text).contains("HackerRank").doesNotContain("<", "&lt;", "&quot;", "&nbsp;");
        assertThat(text).doesNotContain("\n\n\n");
    }

    @Test
    void dropsTheGivenElements() {
        String html = "<div class=\"content-intro\"><p>We are great.</p></div><p>Write Java.</p>";

        assertThat(HtmlText.toPlainText(html, ".content-intro")).isEqualTo("Write Java.");
    }

    @Test
    void nullAndBlankBecomeEmpty() {
        assertThat(HtmlText.toPlainText(null)).isEmpty();
        assertThat(HtmlText.toPlainText("   ")).isEmpty();
        assertThat(HtmlText.fromEscapedHtml(null)).isEmpty();
    }

    @Test
    void truncatesAtAWordBoundary() {
        assertThat(HtmlText.truncate("alpha beta gamma", 12)).isEqualTo("alpha beta");
        assertThat(HtmlText.truncate("short", 12)).isEqualTo("short");
        assertThat(HtmlText.truncate("x".repeat(20), 10)).hasSize(10); // no space to cut at
    }
}
