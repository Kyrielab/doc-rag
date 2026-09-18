package com.zzx.docrag.rag;

/**
 * One parsed citation marker found in the generated answer.
 *
 * @param marker    the raw marker, for example "[1]"
 * @param ordinal   the number inside the marker
 * @param valid     whether it maps to a chunk that was actually in the prompt
 * @param chunkId   resolved chunk id, empty when invalid
 */
public record CitationRef(String marker, int ordinal, boolean valid, String chunkId) {
}
