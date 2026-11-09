package com.acme.rag.document.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Enforces size, signature, UTF-8, and text-control constraints on upload content. */
final class UploadFileContentChecks {
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final int SIGNATURE_SCAN_LIMIT = 1024;

    private UploadFileContentChecks() {}

    static void validateSize(Path source, int maximumBytes) throws IOException {
        long size = Files.size(source);
        if (size == 0 || size > maximumBytes) {
            throw invalid("File is empty or exceeds the configured size limit");
        }
    }

    static void validateContent(String filename, Path source) throws IOException {
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        if (lowerFilename.endsWith(".pdf")) {
            validatePdfFile(source);
        } else if (lowerFilename.endsWith(".chm")) {
            validateChmFile(source);
        } else {
            validateUtf8File(source);
        }
    }

    static void validateContent(String filename, byte[] content) {
        boolean pdfExtension = filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
        boolean chmExtension = filename.toLowerCase(Locale.ROOT).endsWith(".chm");
        if (pdfExtension != hasPdfSignature(content)) {
            throw invalid("Uploaded file content does not match its filename extension");
        }
        if (chmExtension && !hasChmSignature(content)) {
            throw invalid("Uploaded file content does not match its filename extension");
        }
        if (!pdfExtension && !chmExtension) {
            validateUtf8Text(content);
        }
    }

    private static void validatePdfFile(Path source) throws IOException {
        try (var input = Files.newInputStream(source)) {
            if (!hasPdfSignature(input.readNBytes(SIGNATURE_SCAN_LIMIT))) {
                throw invalid("Uploaded file content does not match its filename extension");
            }
        }
    }

    private static void validateChmFile(Path source) throws IOException {
        try (var input = Files.newInputStream(source)) {
            if (!hasChmSignature(input.readNBytes(4))) {
                throw invalid("Uploaded file content does not match its filename extension");
            }
        }
    }

    private static void validateUtf8File(Path source) throws IOException {
        try (var reader = new BufferedReader(new java.io.InputStreamReader(Files.newInputStream(source),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            int value;
            while ((value = reader.read()) != -1) {
                if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
                    throw invalid("Text upload contains binary control characters");
                }
            }
        } catch (java.nio.charset.MalformedInputException | java.nio.charset.UnmappableCharacterException exception) {
            throw invalid("Text upload must be valid UTF-8");
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

    private static boolean hasChmSignature(byte[] content) {
        return content.length >= 4 && content[0] == 'I' && content[1] == 'T'
                && content[2] == 'S' && content[3] == 'F';
    }

    private static IngestionException invalid(String message) {
        return new IngestionException(IngestionException.Kind.INVALID_INPUT, message);
    }
}
