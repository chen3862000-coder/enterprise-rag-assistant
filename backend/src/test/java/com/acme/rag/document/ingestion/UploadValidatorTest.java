package com.acme.rag.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.rag.document.parsing.ChmDocumentParser;
import com.acme.rag.document.parsing.PdfDocumentParser;
import com.acme.rag.document.parsing.TextDocumentParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadValidatorTest {
    private final UploadValidator validator = new UploadValidator(new UploadContentValidator(
            List.of(new TextDocumentParser(), new PdfDocumentParser(), new ChmDocumentParser("7z", 10, 1024, 512, 512)), 1024));

    @TempDir
    Path temporaryDirectory;

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
    void acceptsChmSignatureForByteAndFileUploads() throws Exception {
        byte[] content = {'I', 'T', 'S', 'F', 1};
        Path file = temporaryDirectory.resolve("manual.chm");
        Files.write(file, content);

        assertThat(validator.validate(request("manual.chm", new String(content, java.nio.charset.StandardCharsets.US_ASCII)))
                .content()).containsExactly(content);
        validator.validateFile("manual.chm", file);
    }

    @Test
    void rejectsFilesOverTheConfiguredLimitAndInvalidUtf8() throws Exception {
        Path oversized = temporaryDirectory.resolve("large.txt");
        Files.write(oversized, new byte[1025]);
        assertThatThrownBy(() -> validator.validateFile("large.txt", oversized))
                .isInstanceOf(IngestionException.class).hasMessageContaining("size limit");

        Path invalidText = temporaryDirectory.resolve("invalid.txt");
        Files.write(invalidText, new byte[] {(byte) 0xC3, 0x28});
        assertThatThrownBy(() -> validator.validateFile("invalid.txt", invalidText))
                .isInstanceOf(IngestionException.class).hasMessageContaining("valid UTF-8");
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

    private static IngestionRequest request(String filename, byte[] content) {
        return new IngestionRequest("doc-test", "Test", "ENGINEERING", "INTERNAL", Set.of("ENGINEERING"),
                filename, content, null);
    }
}
