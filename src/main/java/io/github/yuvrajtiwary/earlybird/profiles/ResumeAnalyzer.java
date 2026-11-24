package io.github.yuvrajtiwary.earlybird.profiles;

import io.github.yuvrajtiwary.earlybird.enrich.GeminiException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * The resume pipeline: PDF → text (limited) → contact details removed → Gemini → {@link ResumeFacts}.
 * Nothing is written to disk except PDFBox's own temp scratch files, which it deletes; the text lives only
 * in memory for the length of this call.
 */
public class ResumeAnalyzer {

    static final int MAX_PAGES = 5;
    static final int MAX_CHARS = 15_000;
    static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(20);

    /** Why a resume couldn't be read, so the bot can tell the user something useful. */
    public static final class ResumeException extends Exception {
        public enum Reason { NOT_A_PDF, PASSWORD_PROTECTED, NO_TEXT, TOO_SLOW, AI_BUSY, AI_BUDGET_USED_UP }

        private final Reason reason;

        public ResumeException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final ResumeParser parser;

    public ResumeAnalyzer(ResumeParser parser) {
        this.parser = parser;
    }

    public ResumeFacts analyze(byte[] pdf) throws ResumeException {
        String text = extractWithTimeout(pdf);
        if (text.strip().length() < 30) {
            throw new ResumeException(ResumeException.Reason.NO_TEXT, "no extractable text (scanned image?)");
        }
        String clean = ContactStripper.strip(text);
        try {
            return parser.parse(clean);
        } catch (GeminiException e) {
            throw new ResumeException(e.kind() == GeminiException.Kind.BUDGET_EXHAUSTED
                    ? ResumeException.Reason.AI_BUDGET_USED_UP
                    : ResumeException.Reason.AI_BUSY, e.getMessage());
        }
    }

    private static String extractWithTimeout(byte[] pdf) throws ResumeException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> text = executor.submit(() -> PdfText.extract(pdf, MAX_PAGES, MAX_CHARS));
            try {
                return text.get(EXTRACT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                text.cancel(true);
                throw new ResumeException(ResumeException.Reason.TOO_SLOW, "PDF took too long to read");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof InvalidPasswordException) {
                    throw new ResumeException(ResumeException.Reason.PASSWORD_PROTECTED, "PDF is password-protected");
                }
                if (e.getCause() instanceof IOException) {
                    throw new ResumeException(ResumeException.Reason.NOT_A_PDF, "not a readable PDF");
                }
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResumeException(ResumeException.Reason.TOO_SLOW, "interrupted");
            }
        }
    }
}
