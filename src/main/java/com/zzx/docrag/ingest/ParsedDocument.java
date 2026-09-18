package com.zzx.docrag.ingest;

/**
 * Raw text extracted from a source document.
 *
 * @param title document title, defaults to the file name
 * @param text  plain text with paragraphs separated by newlines
 */
public record ParsedDocument(String title, String text) {
}
