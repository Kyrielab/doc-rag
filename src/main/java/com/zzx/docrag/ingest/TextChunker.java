package com.zzx.docrag.ingest;

import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.es.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document into overlapping chunks.
 *
 * <p>Why this is a class rather than a library call: chunking is the single highest-leverage
 * knob in a RAG pipeline. Too large and retrieval precision collapses; too small and every
 * answer is missing its context. Making it explicit is what allows the evaluation harness to
 * prove which setting wins.
 *
 * <p>Strategy:
 * <ol>
 *   <li>normalize whitespace so the same document always chunks identically</li>
 *   <li>split into paragraphs, then into sentences ending in 。！？!?. or newline</li>
 *   <li>greedily pack sentences up to {@code chunkSize}</li>
 *   <li>carry the tail {@code chunkOverlap} characters into the next chunk so a fact that
 *       straddles a boundary is still fully present in at least one chunk</li>
 * </ol>
 */
@Component
public class TextChunker {

    private final RagProperties ragProperties;

    public TextChunker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * @param document parsed source document
     * @param docId    stable document id, used to build chunk ids
     * @return chunks in document order
     */
    public List<Chunk> chunk(ParsedDocument document, String docId) {
        String normalized = normalize(document.text());
        List<String> pieces = List.of(normalized.split("(?<=[.!?。！？；;])\\s*|\\n+"));

        int size = ragProperties.chunkSize();
        int overlap = ragProperties.chunkOverlap();
        List<String> texts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String piece : pieces) {
            String sentence = piece.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            // A single sentence longer than the budget has to be hard-split, otherwise the
            // packing loop below can never flush.
            if (sentence.length() > size) {
                for (String slice : hardSplit(sentence, size)) {
                    if (current.length() > 0) {
                        texts.add(current.toString().trim());
                        current.setLength(0);
                    }
                    texts.add(slice);
                }
                continue;
            }
            if (current.length() + sentence.length() + 1 > size && current.length() > 0) {
                String finished = current.toString().trim();
                texts.add(finished);
                current.setLength(0);
                String tail = tail(finished, overlap);
                if (!tail.isEmpty()) {
                    current.append(tail);
                }
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(sentence);
        }
        if (!current.toString().isBlank()) {
            texts.add(current.toString().trim());
        }

        List<Chunk> chunks = new ArrayList<>(texts.size());
        for (int ordinal = 0; ordinal < texts.size(); ordinal++) {
            String content = texts.get(ordinal);
            chunks.add(new Chunk(
                    Chunk.buildChunkId(docId, ordinal),
                    docId,
                    document.title(),
                    document.title(),
                    ordinal,
                    content,
                    null // vectors are filled in by the ingestion service
            ));
        }
        return chunks;
    }

    /** Collapses runs of whitespace and normalizes line endings so chunking is deterministic. */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> hardSplit(String sentence, int size) {
        List<String> slices = new ArrayList<>();
        for (int start = 0; start < sentence.length(); start += size) {
            slices.add(sentence.substring(start, Math.min(sentence.length(), start + size)).trim());
        }
        return slices;
    }

    private String tail(String text, int overlap) {
        if (overlap <= 0 || text.isEmpty()) {
            return "";
        }
        return text.length() <= overlap ? text : text.substring(text.length() - overlap);
    }
}
