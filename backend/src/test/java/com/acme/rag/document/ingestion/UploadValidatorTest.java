package com.acme.rag.document.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.rag.document.parsing.PdfDocumentParser;
import com.acme.rag.document.parsing.TextDocumentParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UploadValidatorTest {
    private final UploadValidator validator = new UploadValidator(new UploadContentValidator(
            List.of(new TextDocumentParser(), new PdfDocumentParser()), 1024));

    @Test
    void rejectsPlainTextDisguisedAsPdf() {
        assertThatThrownBy(() -> validator.validate(request("spoof.pdf", "not a pdf")))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsPdfDisguisedAsPlainText() {
        assertThatThrownBy(() -> validator.validate(request("spoof.txt", "%PDF-1.7")))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsEmptyContentAndInvalidRoleWithClientInputClassification() {
        assertThatThrownBy(() -> validator.validate(request("empty.txt", "")))
                .isInstanceOf(IngestionException.class)
                .extracting(exception -> ((IngestionException) exception).kind())
                .isEqualTo(IngestionException.Kind.INVALID_INPUT);
        IngestionRequest invalidRole = new IngestionRequest("doc-test", "Test", "ENG", "INTERNAL",
                Set.of("UNKNOWN"), "test.txt", "text".getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        assertThatThrownBy(() -> validator.validate(invalidRole))
                .isInstanceOf(IngestionException.class)
                .extracting(exception -> ((IngestionException) exception).kind())
                .isEqualTo(IngestionException.Kind.INVALID_INPUT);
    }

    private static IngestionRequest request(String filename, String content) {
        return new IngestionRequest("doc-test", "Test", "ENGINEERING", "INTERNAL", Set.of("ENGINEERING"),
                filename, content.getBytes(java.nio.charset.StandardCharsets.US_ASCII), null);
    }
}
