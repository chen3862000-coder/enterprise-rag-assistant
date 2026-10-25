package com.acme.rag.document.parsing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TextDocumentParser implements DocumentParser {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    @Override
    public boolean supports(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        String normalized = new String(content, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        List<ParsedDocument.Section> sections = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        for (String line : normalized.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line.trim());
            if (matcher.matches()) {
                addSection(sections, String.join(" > ", headings), body);
                int level = matcher.group(1).length();
                while (headings.size() >= level) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(matcher.group(2).trim());
            } else {
                body.append(line).append('\n');
            }
        }
        addSection(sections, String.join(" > ", headings), body);
        if (sections.isEmpty()) {
            sections.add(new ParsedDocument.Section("", "", null));
        }
        return new ParsedDocument(sections);
    }

    private static void addSection(List<ParsedDocument.Section> sections, String heading, StringBuilder body) {
        String text = body.toString().strip();
        if (!text.isEmpty()) {
            sections.add(new ParsedDocument.Section(heading, text, null));
        }
        body.setLength(0);
    }
}
