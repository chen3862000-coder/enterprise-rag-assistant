package com.acme.rag.document.parsing;

import com.acme.rag.document.ingestion.IngestionException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Converts bounded CHM HTML bytes to normalized visible text. */
final class ChmHtmlTextExtractor {
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*['\\\"]?([\\w.-]+)", Pattern.CASE_INSENSITIVE);

    private ChmHtmlTextExtractor() {}

    static String extract(byte[] content) {
        TextCallback callback = new TextCallback();
        try {
            new ParserDelegator().parse(new java.io.StringReader(decode(content)), callback, true);
        } catch (IOException exception) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "Unable to parse CHM HTML", exception);
        }
        return normalize(callback.value());
    }

    private static String decode(byte[] content) {
        List<String> candidates = declaredCharset(content);
        candidates.addAll(List.of("UTF-8", "GB18030"));
        for (String candidate : candidates) {
            String decoded = decodeStrict(content, candidate);
            if (decoded != null) {
                return decoded;
            }
        }
        return new String(content, Charset.forName("GB18030"));
    }

    private static List<String> declaredCharset(byte[] content) {
        String prefix = new String(content, 0, Math.min(content.length, 8192), StandardCharsets.ISO_8859_1);
        Matcher matcher = CHARSET.matcher(prefix);
        List<String> candidates = new ArrayList<>();
        if (matcher.find()) {
            candidates.add(matcher.group(1));
        }
        return candidates;
    }

    private static String decodeStrict(byte[] content, String candidate) {
        try {
            return Charset.forName(candidate).newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content)).toString();
        } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replace('\r', ' ')
                .replaceAll("[\\t ]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static final class TextCallback extends HTMLEditorKit.ParserCallback {
        private static final List<String> IGNORED_TAGS = List.of("script", "style", "noscript", "head");
        private static final List<String> LINE_TAGS = List.of("br", "p", "div", "tr", "li", "h1", "h2", "h3", "h4");
        private final StringBuilder output = new StringBuilder();
        private int ignoredDepth;

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            String name = tag.toString();
            if (IGNORED_TAGS.contains(name)) {
                ignoredDepth++;
            } else if (ignoredDepth == 0 && LINE_TAGS.contains(name)) {
                output.append('\n');
            } else if (ignoredDepth == 0 && List.of("td", "th").contains(name)) {
                output.append(" | ");
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            String name = tag.toString();
            if (IGNORED_TAGS.contains(name) && ignoredDepth > 0) {
                ignoredDepth--;
            } else if (ignoredDepth == 0 && LINE_TAGS.subList(1, LINE_TAGS.size()).contains(name)) {
                output.append('\n');
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            if (ignoredDepth == 0) {
                output.append(data);
            }
        }

        private String value() {
            return output.toString();
        }
    }
}
