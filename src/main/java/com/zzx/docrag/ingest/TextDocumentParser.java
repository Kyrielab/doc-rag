package com.zzx.docrag.ingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plain text and Markdown. */
@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    @Override
    public ParsedDocument parse(Path file, String title) throws IOException {
        return new ParsedDocument(title, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Override
    public ParsedDocument parse(InputStream in, String title) throws IOException {
        return new ParsedDocument(title, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
}
