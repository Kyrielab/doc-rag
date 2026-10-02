package com.zzx.docrag.ingest;

import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.es.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    private static final int CHUNK_SIZE = 200;
    private static final int OVERLAP = 40;

    private final TextChunker chunker = new TextChunker(
            new RagProperties(CHUNK_SIZE, OVERLAP, 8, 30, 0.35, 60, true, true, false, 600,
                    1.0, 1.0, 0, false, false, 250, 0));

    private static String sentence(String marker, int length) {
        StringBuilder sb = new StringBuilder(marker).append(' ');
        while (sb.length() < length) {
            sb.append("word ");
        }
        return sb.append(". ").toString();
    }

    @Test
    @DisplayName("chunks respect the size budget")
    void respectsSizeBudget() {
        String text = sentence("alpha", 80).repeat(12);

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("manual", text), "doc-1");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(CHUNK_SIZE + OVERLAP + 110));
    }

    @Test
    @DisplayName("adjacent chunks overlap so a fact on a boundary survives")
    void adjacentChunksOverlap() {
        String text = sentence("alpha", 150) + sentence("beta", 150) + sentence("gamma", 150);

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("manual", text), "doc-1");

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).content();
            String current = chunks.get(i).content();
            String previousTail = previous.substring(Math.max(0, previous.length() - OVERLAP));
            assertThat(current).contains(previousTail.substring(0, Math.min(20, previousTail.length())));
        }
    }

    @Test
    @DisplayName("chunk ids are deterministic so re-ingestion replaces instead of duplicating")
    void deterministicChunkIds() {
        ParsedDocument document = new ParsedDocument("manual", sentence("alpha", 120).repeat(5));

        List<Chunk> first = chunker.chunk(document, "doc-1");
        List<Chunk> second = chunker.chunk(document, "doc-1");

        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).chunkId()).isEqualTo(second.get(i).chunkId());
            assertThat(first.get(i).content()).isEqualTo(second.get(i).content());
        }
        assertThat(first.get(0).chunkId()).isEqualTo("doc-1#0");
    }

    @Test
    @DisplayName("a single oversized sentence is hard-split rather than emitted whole")
    void hardSplitsOversizedSentence() {
        String text = "x".repeat(CHUNK_SIZE * 3) + ".";

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("manual", text), "doc-1");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(CHUNK_SIZE));
    }

    @Test
    @DisplayName("blank input yields no chunks instead of one empty chunk")
    void blankInputYieldsNothing() {
        assertThat(chunker.chunk(new ParsedDocument("manual", "   \n\n  "), "doc-1")).isEmpty();
    }
}
