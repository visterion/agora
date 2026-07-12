package de.visterion.agora.fetch.reference.change;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Apache PDFBox extraction path end-to-end against a PDF generated in-memory,
 * so no checked-in binary fixture is needed and the PDFBox wiring is genuinely validated (not
 * mocked). The provider-level tests stub the extractor; this one proves the seam's production impl.
 */
class PdfBoxTextExtractorTest {

    private static byte[] pdfWithLines(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(16);
                cs.newLineAtOffset(50, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test void extractsTextLayerAndFeedsTheConstituentParser() throws Exception {
        byte[] pdf = pdfWithLines(
                "Company Symbol Industry",
                "AARDVARK THERAPEUTICS AARD Health Care",
                "BROOKFIELD ASSET MANAGEM BAM Financials");

        String text = new PdfBoxTextExtractor().extractText(pdf);
        assertThat(text).contains("AARDVARK THERAPEUTICS AARD Health Care")
                .contains("BROOKFIELD ASSET MANAGEM BAM Financials");

        List<RussellConstituentListParser.Row> rows = RussellConstituentListParser.parse(text);
        assertThat(rows).extracting(RussellConstituentListParser.Row::ticker)
                .containsExactly("AARD", "BAM");
    }

    @Test void emptyInputYieldsEmptyString() {
        assertThat(new PdfBoxTextExtractor().extractText(new byte[0])).isEmpty();
        assertThat(new PdfBoxTextExtractor().extractText(null)).isEmpty();
    }

    @Test void corruptPdfThrowsForTheProviderToCatch() {
        assertThat(catchException(() -> new PdfBoxTextExtractor().extractText("not a pdf".getBytes())))
                .isInstanceOf(RuntimeException.class);
    }

    private static Throwable catchException(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }
}
