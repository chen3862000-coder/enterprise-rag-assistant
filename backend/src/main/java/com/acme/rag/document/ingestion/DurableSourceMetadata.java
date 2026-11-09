package com.acme.rag.document.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

/** Serializes bounded, content-free upload metadata in a private sibling file. */
final class DurableSourceMetadata {
    private static final int MAXIMUM_METADATA_BYTES = 16_384;
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final DurableSourceFiles files;

    DurableSourceMetadata(DurableSourceFiles files) {
        this.files = files;
    }

    void write(DurableSourceStorage.StoredSource source, ValidatedUpload upload) {
        requireStoredSource(source.path());
        Path target = DurableSourceFiles.metadataPath(source.path());
        String encoded = encode(upload.documentId()) + "\n" + encode(upload.title()) + "\n"
                + encode(upload.department()) + "\n" + encode(upload.classification()) + "\n"
                + encode(String.join(",", upload.roles()));
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            DurableSourceFiles.setPermissions(target, PRIVATE_FILE);
            output.write(encoded.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            files.delete(source.path());
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "Unable to persist ingestion metadata", exception);
        }
    }

    IngestionMetadata read(Path source) {
        requireStoredSource(source);
        Path target = DurableSourceFiles.metadataPath(source);
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "Durable ingestion metadata is missing or invalid");
        }
        try (InputStream input = Files.newInputStream(target)) {
            String[] fields = new String(input.readNBytes(MAXIMUM_METADATA_BYTES), StandardCharsets.UTF_8)
                    .split("\\n", -1);
            if (fields.length != 5) {
                throw new IOException("Invalid durable metadata");
            }
            return new IngestionMetadata(decode(fields[0]), decode(fields[1]), decode(fields[2]), decode(fields[3]),
                    Arrays.stream(decode(fields[4]).split(",")).collect(Collectors.toUnmodifiableSet()));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "Durable ingestion metadata is missing or invalid", exception);
        }
    }

    private void requireStoredSource(Path source) {
        if (!files.isStoredSource(source)) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "Durable ingestion source is missing or invalid");
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
