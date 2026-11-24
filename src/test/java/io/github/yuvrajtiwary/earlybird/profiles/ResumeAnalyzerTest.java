package io.github.yuvrajtiwary.earlybird.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.enrich.GeminiException;
import io.github.yuvrajtiwary.earlybird.enrich.SkillVocabulary;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeAnalyzer.ResumeException;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeAnalyzer.ResumeException.Reason;
import io.github.yuvrajtiwary.earlybird.support.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResumeAnalyzerTest {

    /** A parser that records what it would have sent to Gemini. */
    private static final class RecordingParser extends ResumeParser {
        final AtomicReference<String> sent = new AtomicReference<>();
        GeminiException failWith;

        RecordingParser() {
            super(null, SkillVocabulary.fromClasspath(), Clock.systemUTC());
        }

        @Override
        public ResumeFacts parse(String cleanResumeText) {
            sent.set(cleanResumeText);
            if (failWith != null) {
                throw failWith;
            }
            return new ResumeFacts(List.of("Java"), 1.0, 2025, List.of());
        }
    }

    private final RecordingParser parser = new RecordingParser();
    private final ResumeAnalyzer analyzer = new ResumeAnalyzer(parser);

    @Test
    void contactDetailsAreRemovedBeforeAnythingIsSentToGemini() throws Exception {
        analyzer.analyze(TestPdfs.withLines(TestPdfs.FAKE_RESUME));

        assertThat(parser.sent.get())
                .contains("Spring Boot", "CGPA 8.4")
                .doesNotContain("Asha", "@example.com", "98765", "linkedin", "github.com", "560001");
    }

    @Test
    void reportsWhyAResumeCouldNotBeRead() {
        assertReason(TestPdfs.withLines(List.of()), Reason.NO_TEXT);
        assertReason(TestPdfs.pages(List.of(List.of("hidden")), "pw"), Reason.PASSWORD_PROTECTED);
        assertReason("just text".getBytes(StandardCharsets.UTF_8), Reason.NOT_A_PDF);
    }

    @Test
    void geminiProblemsBecomeAFriendlyReason() {
        parser.failWith = new GeminiException(GeminiException.Kind.UNAVAILABLE, "503");
        assertReason(TestPdfs.withLines(TestPdfs.FAKE_RESUME), Reason.AI_BUSY);

        parser.failWith = new GeminiException(GeminiException.Kind.BUDGET_EXHAUSTED, "used up");
        assertReason(TestPdfs.withLines(TestPdfs.FAKE_RESUME), Reason.AI_BUDGET_USED_UP);
    }

    private void assertReason(byte[] pdf, Reason reason) {
        assertThatThrownBy(() -> analyzer.analyze(pdf))
                .isInstanceOfSatisfying(ResumeException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
