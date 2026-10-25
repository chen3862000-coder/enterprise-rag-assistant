package com.acme.rag.document.ingestion;

import com.acme.rag.document.parsing.DocumentParser;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class UploadValidator {
    private static final Set<String> CLASSIFICATIONS = Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> ROLES = Set.of("PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN");

    private final List<DocumentParser> parsers;
    private final int maxFileBytes;

    UploadValidator(List<DocumentParser> parsers, @Value("${app.ingestion.max-file-bytes:10485760}") int maxFileBytes) {
        this.parsers = List.copyOf(parsers);
        this.maxFileBytes = maxFileBytes;
    }

    ValidatedUpload validate(IngestionRequest request) {
        validateDocumentId(request);
        String filename = safeFilename(request.filename());
        byte[] content = validateContent(request.content());
        String title = required(request.title(), "title");
        String department = required(request.department(), "department");
        String classification = required(request.classification(), "classification").toUpperCase(Locale.ROOT);
        validateClassification(classification);
        Set<String> roles = request.allowedRoles().stream().map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        validateRoles(roles);
        validateSupportedFile(filename);
        return new ValidatedUpload(request.documentId().strip(), title, department, classification, roles, filename,
                content, request.effectiveAt() == null ? Instant.now() : request.effectiveAt());
    }

    private static void validateDocumentId(IngestionRequest request) {
        if (request == null || request.documentId() == null || request.documentId().isBlank()) {
            throw new IngestionException("A stable logical document ID is required");
        }
    }

    private byte[] validateContent(byte[] content) {
        if (content.length == 0 || content.length > maxFileBytes) {
            throw new IngestionException("File is empty or exceeds the configured size limit");
        }
        return content;
    }

    private static void validateClassification(String classification) {
        if (!CLASSIFICATIONS.contains(classification)) {
            throw new IngestionException("Unsupported document classification");
        }
    }

    private static void validateRoles(Set<String> roles) {
        if (roles.isEmpty() || !ROLES.containsAll(roles)) {
            throw new IngestionException("At least one valid allowed role is required");
        }
    }

    private void validateSupportedFile(String filename) {
        if (parsers.stream().noneMatch(parser -> parser.supports(filename))) {
            throw new IngestionException("Only Markdown, plain text, and PDF files are supported");
        }
    }

    private static String safeFilename(String suppliedFilename) {
        String normalized = suppliedFilename == null ? "" : suppliedFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (filename.isBlank() || filename.length() > 255 || filename.contains("..")) {
            throw new IngestionException("Invalid source filename");
        }
        return filename;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IngestionException("Document " + field + " is required");
        }
        return value.strip();
    }
}
