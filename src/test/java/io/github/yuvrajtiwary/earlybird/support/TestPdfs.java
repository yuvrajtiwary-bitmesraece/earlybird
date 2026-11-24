package io.github.yuvrajtiwary.earlybird.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds synthetic resume PDFs in tests, so no real person's resume is ever committed. ASCII text only. */
public final class TestPdfs {

    /** A made-up resume full of contact details that must never reach Gemini. */
    public static final List<String> FAKE_RESUME = List.of(
            "Asha Kumari",
            "asha.kumari@example.com | +91 98765 43210 | linkedin.com/in/asha-kumari-dev | github.com/ashak",
            "Flat 12, Green Apartments, MG Road, Bengaluru 560001",
            "EDUCATION",
            "B.Tech in Computer Science, Example Institute of Technology, 2021 - 2025, CGPA 8.4",
            "EXPERIENCE",
            "Backend Developer, SampleSoft (Aug 2025 - present): Java 17, Spring Boot, Kafka, Docker, AWS.",
            "SKILLS",
            "Java, Python, JS, SQL, springboot, React, Git, Linux");

    private TestPdfs() {}

    public static byte[] withLines(List<String> lines) {
        return pages(List.of(lines), null);
    }

    /** One page per entry; each entry is that page's lines. */
    public static byte[] pages(List<List<String>> pages, String userPassword) {
        try (PDDocument doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.newLineAtOffset(40, 750);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLineAtOffset(0, -14);
                    }
                    content.endText();
                }
            }
            if (userPassword != null) {
                var policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
