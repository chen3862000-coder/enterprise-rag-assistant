package com.acme.rag.document.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfDocumentParserTest {
    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void extractsTextAndPreservesPageNumberFromRealPdfFixture() throws Exception {
        byte[] pdf = pdfWithText("Rate limit is 1000 requests per minute.");

        ParsedDocument parsed = parser.parse(pdf);

        assertThat(parsed.sections()).containsExactly(new ParsedDocument.Section("",
                "Rate limit is 1000 requests per minute.", 1));
    }

    @Test
    void rejectsCorruptedPdfAsUnprocessableContent() {
        assertThatThrownBy(() -> parser.parse("%PDF-not-a-pdf".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to parse PDF document");
    }

    @Test
    void rejectsImageOnlyScannedPdfWhenNoTextCanBeExtracted() throws Exception {
        assertThatThrownBy(() -> parser.parse(blankPdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanned PDFs are unsupported");
    }

    private static byte[] pdfWithText(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
