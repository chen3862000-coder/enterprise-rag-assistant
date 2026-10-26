package com.acme.rag.document.parsing;

import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try (var pdf = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<ParsedDocument.Section> sections = new ArrayList<>();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf).strip();
                if (!text.isEmpty()) {
                    sections.add(new ParsedDocument.Section("", text, page));
                }
            }
            if (sections.isEmpty()) {
                throw new IllegalArgumentException("PDF contains no extractable text; scanned PDFs are unsupported");
            }
            return new ParsedDocument(sections);
    } catch (IOException e) {
            throw new IllegalArgumentException("Unable to parse PDF document", e);
        }
    }
}
