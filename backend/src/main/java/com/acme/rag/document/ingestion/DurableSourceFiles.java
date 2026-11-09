package com.acme.rag.document.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Owns source-file creation and enforces the private-root path boundary. */
final class DurableSourceFiles {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path root;
    private final long maximumBytes;

    DurableSourceFiles(Path root, long maximumBytes) {
        this.root = root;
        this.maximumBytes = maximumBytes;
    }

    Path root() {
        return root;
    }

    DurableSourceStorage.StoredSource store(InputStream input) {
        Path source = null;
        boolean stored = false;
        try {
            source = createPrivateFile();
            DurableSourceStorage.StoredSource result = copyAndHash(input, source);
            stored = true;
            return result;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IngestionException(IngestionException.Kind.INVALID_INPUT,
                    "Unable to store uploaded source", exception);
        } finally {
            if (!stored) {
                deleteAfterFailure(source);
            }
        }
    }

    private Path createPrivateFile() throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Ingestion source directory must not be a symbolic link");
        }
        setPermissions(root, PRIVATE_DIRECTORY);
        Path source = Files.createTempFile(root, "ingestion-", ".source");
        try {
            setPermissions(source, PRIVATE_FILE);
        } catch (IOException exception) {
            Files.deleteIfExists(source);
            throw exception;
        }
        return source;
    }

    private DurableSourceStorage.StoredSource copyAndHash(InputStream input, Path source)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (OutputStream output = Files.newOutputStream(source, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                checkSize(size);
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (size == 0) {
            throw new IngestionException(IngestionException.Kind.INVALID_INPUT, "Uploaded file is empty");
        }
        return new DurableSourceStorage.StoredSource(source, size, HexFormat.of().formatHex(digest.digest()));
    }

    private void checkSize(long size) {
        if (size > maximumBytes) {
            throw new IngestionException(IngestionException.Kind.INVALID_INPUT,
                    "File is empty or exceeds the configured size limit");
        }
    }

    void delete(Path source) {
        Path normalized = safeSourcePath(source);
        if (normalized == null || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path metadata = metadataPath(normalized);
        if (Files.isSymbolicLink(metadata) || Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.deleteIfExists(normalized);
            Files.deleteIfExists(metadata);
        } catch (IOException exception) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "Unable to remove ingestion source", exception);
        }
    }

    boolean isStoredSource(Path source) {
        return safeSourcePath(source) != null;
    }

    private Path safeSourcePath(Path source) {
        if (source == null) {
            return null;
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (!isSafeSourceLocation(normalized)) {
            return null;
        }
        return Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) ? normalized : null;
    }

    private boolean isSafeSourceLocation(Path source) {
        Path filename = source.getFileName();
        return !Files.isSymbolicLink(root) && source.startsWith(root) && !source.equals(root)
                && root.equals(source.getParent()) && filename != null
                && !filename.toString().endsWith(".meta") && !Files.isSymbolicLink(source);
    }

    private void deleteAfterFailure(Path source) {
        if (source != null) {
            delete(source);
        }
    }

    static Path metadataPath(Path source) {
        return source.resolveSibling(source.getFileName() + ".meta");
    }

    static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException exception) {
            if (!path.toFile().setReadable(false, false) || !path.toFile().setWritable(false, false)
                    || !path.toFile().setReadable(true, true) || !path.toFile().setWritable(true, true)) {
                throw new IOException("Unable to restrict ingestion source permissions", exception);
            }
        }
    }
}
