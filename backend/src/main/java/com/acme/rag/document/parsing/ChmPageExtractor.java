package com.acme.rag.document.parsing;

import com.acme.rag.document.ingestion.IngestionException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads extracted regular HTML files within configured per-page and aggregate bounds. */
final class ChmPageExtractor {
    private final long maximumPageBytes;
    private final long maximumExpandedBytes;

    ChmPageExtractor(long maximumPageBytes, long maximumExpandedBytes) {
        this.maximumPageBytes = maximumPageBytes;
        this.maximumExpandedBytes = maximumExpandedBytes;
    }

    ParsedDocument read(Path root, List<ChmMember> pages) throws IOException {
        List<ParsedDocument.Section> sections = new ArrayList<>();
        long extractedBytes = 0;
        for (ChmMember page : pages) {
            Path target = root.resolve(page.path()).normalize();
            if (!isRegularSafeFile(root, target)) {
                continue;
            }
            long size = Files.size(target);
            extractedBytes += size;
            validateBounds(size, extractedBytes);
            String text = ChmHtmlTextExtractor.extract(readPage(target));
            if (!text.isBlank() && !isFrameFallback(text)) {
                appendSections(sections, page.path(), text);
            }
        }
        if (sections.isEmpty()) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "CHM archive contains no readable HTML text");
        }
        return new ParsedDocument(sections);
    }

    private static boolean isRegularSafeFile(Path root, Path target) throws IOException {
        return target.startsWith(root) && !Files.isSymbolicLink(target) && Files.isRegularFile(target);
    }

    private void validateBounds(long pageSize, long extractedBytes) {
        if (pageSize > maximumPageBytes || extractedBytes > maximumExpandedBytes) {
            throw ChmDocumentParser.invalid("Extracted CHM pages exceed the configured limit");
        }
    }

    private byte[] readPage(Path target) throws IOException {
        try (InputStream input = Files.newInputStream(target)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(maximumPageBytes + 1));
            if (bytes.length > maximumPageBytes) {
                throw ChmDocumentParser.invalid("Extracted CHM page exceeds the configured size limit");
            }
            return bytes;
        }
    }

    private static boolean isFrameFallback(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("this page uses frames") || text.contains("此页面使用了框架，而您的浏览器不支持框架");
    }

    private static void appendSections(List<ParsedDocument.Section> sections, String path, String content) {
        StringBuilder section = new StringBuilder();
        int sectionIndex = 0;
        for (String row : content.lines().map(String::strip).filter(value -> !value.isBlank()).toList()) {
            if (section.length() > 0 && section.length() + row.length() + 1 > 1800) {
                sections.add(new ParsedDocument.Section(path + " > section " + ++sectionIndex, section.toString(), null));
                section.setLength(0);
            }
            if (!section.isEmpty()) {
                section.append('\n');
            }
            section.append(row);
        }
        if (!section.isEmpty()) {
            sections.add(new ParsedDocument.Section(path + " > section " + ++sectionIndex, section.toString(), null));
        }
    }
}
