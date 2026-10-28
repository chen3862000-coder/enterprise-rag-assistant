package com.acme.rag.document.ingestion;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class UploadValidator {
    private static final Set<String> CLASSIFICATIONS = Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> ROLES = Set.of("PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN");

    private final UploadContentValidator contentValidator;

    UploadValidator(UploadContentValidator contentValidator) {
        this.contentValidator = contentValidator;
    }

    ValidatedUpload validate(IngestionRequest request) {
        validateDocumentId(request);
        String filename = safeFilename(request.filename());
        byte[] content = contentValidator.validate(filename, request.content());
        String title = required(request.title(), "title");
        String department = required(request.department(), "department");
        String classification = required(request.classification(), "classification").toUpperCase(Locale.ROOT);
        validateClassification(classification);
        Set<String> roles = request.allowedRoles().stream().map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        validateRoles(roles);
        return new ValidatedUpload(request.documentId().strip(), title, department, classification, roles, filename,
                content, request.effectiveAt() == null ? Instant.now() : request.effectiveAt(), request.seedManaged());
    }

    private static void validateDocumentId(IngestionRequest request) {
        if (request == null || request.documentId() == null || request.documentId().isBlank()) {
            throw invalid("A stable logical document ID is required");
        }
    }

    private static void validateClassification(String classification) {
        if (!CLASSIFICATIONS.contains(classification)) {
            throw new IngestionException(IngestionException.Kind.INVALID_INPUT, "Unsupported document classification");
        }
    }

    private static void validateRoles(Set<String> roles) {
        if (roles.isEmpty() || !ROLES.containsAll(roles)) {
            throw new IngestionException(IngestionException.Kind.INVALID_INPUT,
                    "At least one valid allowed role is required");
        }
    }

    private static String safeFilename(String suppliedFilename) {
        String normalized = suppliedFilename == null ? "" : suppliedFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (filename.isBlank() || filename.length() > 255 || filename.contains("..")) {
            throw invalid("Invalid source filename");
        }
        return filename;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("Document " + field + " is required");
        }
        return value.strip();
    }

    private static IngestionException invalid(String message) {
        return new IngestionException(IngestionException.Kind.INVALID_INPUT, message);
    }
}
