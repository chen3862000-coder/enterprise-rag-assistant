package com.acme.rag.document.ingestion;

import com.acme.rag.document.parsing.DocumentParser;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class UploadContentValidator {
    private final List<DocumentParser> parsers;
    private final int maximumBytes;

    UploadContentValidator(List<DocumentParser> parsers,
                           @Value("${app.ingestion.max-file-bytes:10485760}") int maximumBytes) {
        this.parsers = List.copyOf(parsers);
        this.maximumBytes = maximumBytes;
    }

    byte[] validate(String filename, byte[] content) {
        validateSize(content);
        validateExtension(filename);
        validateContentMatchesExtension(filename, content);
        return content;
    }

    void validateFile(String filename, Path source) {
        try {
            UploadFileContentChecks.validateSize(source, maximumBytes);
            validateExtension(filename);
            UploadFileContentChecks.validateContent(filename, source);
        } catch (java.io.IOException exception) {
            throw new IngestionException(IngestionException.Kind.INVALID_INPUT,
                    "Unable to read uploaded file", exception);
        }
    }

    private void validateSize(byte[] content) {
        if (content == null || content.length == 0 || content.length > maximumBytes) {
            throw invalid("File is empty or exceeds the configured size limit");
        }
    }

    private void validateExtension(String filename) {
        if (parsers.stream().noneMatch(parser -> parser.supports(filename))) {
            throw invalid("Only Markdown, plain text, PDF, and CHM files are supported");
        }
    }

    private void validateContentMatchesExtension(String filename, byte[] content) {
        UploadFileContentChecks.validateContent(filename, content);
    }

    private static IngestionException invalid(String message) {
        return new IngestionException(IngestionException.Kind.INVALID_INPUT, message);
    }
}
