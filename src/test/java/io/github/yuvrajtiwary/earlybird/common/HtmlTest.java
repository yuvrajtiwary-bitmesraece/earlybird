package io.github.yuvrajtiwary.earlybird.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTest {

    @Test
    void escapesTheCharactersTelegramHtmlCaresAbout() {
        assertThat(Html.escape("<b>R&D</b> \"lead\"")).isEqualTo("&lt;b&gt;R&amp;D&lt;/b&gt; &quot;lead&quot;");
    }

    @Test
    void leavesEverythingElseAlone() {
        assertThat(Html.escape("Backend Engineer I — ExampleCo (Bengaluru) ✓")).isEqualTo("Backend Engineer I — ExampleCo (Bengaluru) ✓");
        assertThat(Html.escape(null)).isEmpty();
    }
}
