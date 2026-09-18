package com.zzx.docrag.es;

import java.util.List;

/**
 * Storage and retrieval port for chunks.
 *
 * <p>Two retrievers are exposed separately instead of one "search" method, because the
 * whole point of the project is to be able to measure, fuse and A/B them independently.
 */
public interface ChunkGateway {

    /** Creates the index with its explicit mapping when absent. Idempotent. */
    void ensureIndex();

    /** Upserts a batch of chunks. */
    void index(List<Chunk> chunks);

    /** Removes every chunk of a document. Returns the number of deleted chunks. */
    int deleteByDocId(String docId);

    /** Lexical retrieval (BM25 over the analyzed content field). */
    List<Retrieved> lexicalSearch(String query, int topK);

    /** Dense retrieval over the dense_vector field. */
    List<Retrieved> vectorSearch(float[] queryVector, int topK, double minScore);

    /**
     * Fetches chunks by id, preserving the requested order. Used to rebuild citations for a
     * cached answer, so a cache hit returns exactly the same sources as the original answer.
     */
    List<Chunk> findByIds(List<String> chunkIds);

    /** Document-level aggregation for the management API. */
    List<DocInfo> listDocuments(int limit);
}
