package com.acme.rag.document.ingestion;

import com.acme.rag.document.parsing.DocumentParser;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class UploadContentValidator {
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final int SIGNATURE_SCAN_LIMIT = 1024;
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

    private void validateSize(byte[] content) {
        if (content == null || content.length == 0 || content.length > maximumBytes) {
            throw invalid("File is empty or exceeds the configured size limit");
        }
    }

    private void validateExtension(String filename) {
        if (parsers.stream().noneMatch(parser -> parser.supports(filename))) {
            throw invalid("Only Markdown, plain text, and PDF files are supported");
        }
    }

    private void validateContentMatchesExtension(String filename, byte[] content) {
        boolean pdfExtension = filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
        if (pdfExtension != hasPdfSignature(content)) {
            throw invalid("Uploaded file content does not match its filename extension");
        }
        if (!pdfExtension) {
            validateUtf8Text(content);
        }
    }

    private static void validateUtf8Text(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            boolean hasBinaryControls = text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\t');
            if (hasBinaryControls) {
                throw invalid("Text upload contains binary control characters");
            }
        } catch (CharacterCodingException exception) {
            throw invalid("Text upload must be valid UTF-8");
        }
    }

    private static boolean hasPdfSignature(byte[] content) {
        int lastOffset = Math.min(SIGNATURE_SCAN_LIMIT, content.length - PDF_SIGNATURE.length);
        for (int offset = 0; offset <= lastOffset; offset++) {
            boolean matches = true;
            for (int index = 0; index < PDF_SIGNATURE.length; index++) {
                matches &= content[offset + index] == PDF_SIGNATURE[index];
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static IngestionException invalid(String message) {
        return new IngestionException(IngestionException.Kind.INVALID_INPUT, message);
    }
}
