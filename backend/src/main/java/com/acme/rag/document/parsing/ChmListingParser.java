package com.acme.rag.document.parsing;

import com.acme.rag.document.ingestion.IngestionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses 7-Zip technical listing output while rejecting unsafe member metadata. */
final class ChmListingParser {
    private final int maximumEntries;

    ChmListingParser(int maximumEntries) {
        this.maximumEntries = maximumEntries;
    }

    List<ChmMember> parse(String listing) {
        List<ChmMember> members = new ArrayList<>();
        MemberBlock current = new MemberBlock();
        boolean inMembers = false;
        for (String line : listing.split("\\R")) {
            if (line.equals("----------")) {
                inMembers = true;
                current = new MemberBlock();
            } else if (inMembers) {
                consumeLine(line, current, members);
            }
        }
        addMember(current, members);
        if (members.size() > maximumEntries) {
            throw ChmDocumentParser.invalid("CHM archive contains too many entries");
        }
        return List.copyOf(members);
    }

    private static void consumeLine(String line, MemberBlock current, List<ChmMember> members) {
        if (line.startsWith("Path = ")) {
            addMember(current, members);
            current.start(line.substring(7));
        } else if (current.hasPath() && line.startsWith("Size = ")) {
            current.append(line);
            current.readSize(line.substring(7));
        } else if (current.hasPath()) {
            current.append(line);
        }
    }

    private static void addMember(MemberBlock block, List<ChmMember> members) {
        if (!block.hasPath()) {
            return;
        }
        block.validateSafety();
        String path = ChmMember.safePath(block.path());
        if (!path.endsWith("/")) {
            members.add(new ChmMember(path, block.size()));
        }
    }

    private static final class MemberBlock {
        private String path;
        private long size;
        private final StringBuilder metadata = new StringBuilder();

        private void start(String memberPath) {
            path = memberPath;
            size = 0;
            metadata.setLength(0);
            append("Path = " + memberPath);
        }

        private void readSize(String value) {
            try {
                size = Long.parseLong(value);
                if (size < 0) {
                    throw new NumberFormatException("negative size");
                }
            } catch (NumberFormatException exception) {
                throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                        "CHM archive has invalid member metadata", exception);
            }
        }

        private void append(String line) {
            metadata.append(line).append('\n');
        }

        private boolean hasPath() {
            return path != null;
        }

        private String path() {
            return path;
        }

        private long size() {
            return size;
        }

        private void validateSafety() {
            String lower = metadata.toString().toLowerCase(Locale.ROOT);
            if (lower.contains("folder = +") || lower.contains("folder = true")) {
                return;
            }
            if (lower.contains("attributes = ") && (lower.contains("symbolic") || lower.contains("reparse"))) {
                throw ChmDocumentParser.invalid("CHM archive contains an unsafe link");
            }
        }
    }
}
