package com.zzx.docrag.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PDF parsing.
 *
 * <p>Known limitation, and a good thing to be able to talk about: PDF carries no semantic
 * structure, so headings and tables come out as flat text. Production systems either run a
 * layout-aware model (Docling, MinerU, marker) or ask for the source format. Our chunker
 * compensates by splitting on sentence boundaries instead of assuming paragraphs.
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    @Override
    public ParsedDocument parse(Path file, String title) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, title);
        }
    }

    @Override
    public ParsedDocument parse(InputStream in, String title) throws IOException {
        try (PDDocument document = Loader.loadPDF(in.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return new ParsedDocument(title, text);
        }
    }
}
