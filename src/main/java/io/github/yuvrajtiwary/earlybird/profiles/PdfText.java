package io.github.yuvrajtiwary.earlybird.profiles;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Text from a resume PDF with PDFBox 3 (spec section 3.8): only the first pages, capped length, and PDFBox's
 * scratch data in temp files rather than heap ({@code createTempFileOnlyStreamCache}) so an odd PDF can't eat
 * the 512 MB. The PDF is only read, never rendered or executed. The time limit is applied by the caller.
 */
public final class PdfText {

    private PdfText() {}

    /**
     * @throws org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException if the PDF needs a password
     * @throws IOException                                                 if it isn't a readable PDF
     */
    public static String extract(byte[] pdf, int maxPages, int maxChars) throws IOException {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdf), "", null, null,
                IOUtils.createTempFileOnlyStreamCache())) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(maxPages, document.getNumberOfPages()));
            String text = stripper.getText(document);
            return text.length() > maxChars ? text.substring(0, maxChars) : text;
        }
    }
}
