package com.zzx.docrag.ingest;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** DOCX parsing via POI. */
@Component
public class DocxDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".docx");
    }

    @Override
    public ParsedDocument parse(Path file, String title) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, title);
        }
    }

    @Override
    public ParsedDocument parse(InputStream in, String title) throws IOException {
        try (XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return new ParsedDocument(title, extractor.getText());
        }
    }
}
