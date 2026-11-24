package io.github.yuvrajtiwary.earlybird.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.support.TestPdfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.junit.jupiter.api.Test;

class PdfTextTest {

    @Test
    void extractsTheText() throws IOException {
        String text = PdfText.extract(TestPdfs.withLines(TestPdfs.FAKE_RESUME), 5, 15_000);

        assertThat(text).contains("EDUCATION", "Spring Boot", "CGPA 8.4");
    }

    @Test
    void readsOnlyTheFirstPages() throws IOException {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            pages.add(List.of("Page marker " + i));
        }

        String text = PdfText.extract(TestPdfs.pages(pages, null), 5, 15_000);

        assertThat(text).contains("Page marker 1", "Page marker 5").doesNotContain("Page marker 6", "Page marker 7");
    }

    @Test
    void capsTheLength() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lines.add("Line " + i + " with some filler words to make it longer than the cap allows");
        }

        assertThat(PdfText.extract(TestPdfs.withLines(lines), 5, 500)).hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void aPasswordProtectedPdfIsReported() {
        byte[] locked = TestPdfs.pages(List.of(List.of("secret")), "open-sesame");

        assertThatThrownBy(() -> PdfText.extract(locked, 5, 15_000)).isInstanceOf(InvalidPasswordException.class);
    }

    @Test
    void somethingThatIsNotAPdfIsReported() {
        byte[] notPdf = "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> PdfText.extract(notPdf, 5, 15_000)).isInstanceOf(IOException.class);
    }
}
