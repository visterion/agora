package de.visterion.agora.fetch.reference.change;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Apache PDFBox implementation of {@link PdfTextExtractor}. Loads the document from bytes and
 * runs the default {@link PDFTextStripper}, which yields the text layer (empty for an
 * image-only PDF). Wraps {@link IOException} as an unchecked failure so the calling provider's
 * fail-soft catch handles a corrupt/unreadable PDF the same as any other unavailability.
 */
@Component
public class PdfBoxTextExtractor implements PdfTextExtractor {

    @Override
    public String extractText(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return "";
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new IllegalStateException("PDF text extraction failed: " + e.getMessage(), e);
        }
    }
}
