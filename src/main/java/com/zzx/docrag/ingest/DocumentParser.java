package com.zzx.docrag.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Turns a file into plain text. Implementations are selected by content type,
 * so adding a format means adding one class and one registry entry.
 */
public interface DocumentParser {

    /** Whether this parser can handle the given file name. */
    boolean supports(String fileName);

    /** Parses the file. The caller owns the stream. */
    ParsedDocument parse(Path file, String title) throws IOException;

    /** Convenience overload for in-memory content such as pasted text. */
    ParsedDocument parse(InputStream in, String title) throws IOException;
}
