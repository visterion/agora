package de.visterion.agora.fetch.reference.change;

/**
 * Seam over PDF text extraction so the constituent-list parsing logic can be tested against a
 * plain-text fixture without depending on a binary PDF. The production implementation
 * ({@link PdfBoxTextExtractor}) uses Apache PDFBox.
 *
 * <p>Implementations extract the full text layer of a PDF. An image-only PDF that carries no
 * text layer yields an empty string rather than an exception; a corrupt or unreadable PDF may
 * throw. Callers treat any thrown failure as "list unavailable" and degrade gracefully.
 */
@FunctionalInterface
public interface PdfTextExtractor {

    /** Extracted text of the PDF; may throw on an unreadable/corrupt document. */
    String extractText(byte[] pdf);
}
