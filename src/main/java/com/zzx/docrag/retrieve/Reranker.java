package com.zzx.docrag.retrieve;

import java.util.List;

/**
 * Cross-encoder reranking port.
 *
 * <p>Retrieval is cheap and recall-oriented; reranking is expensive and precision-oriented.
 * Splitting them lets us fetch 30 candidates with two fast retrievers and then spend real
 * compute on the 30x1 query-document pairs, which is where most of the quality gain lives.
 */
public interface Reranker {

    /**
     * Reorders candidates by relevance to the query.
     *
     * @param query      user question
     * @param candidates fused candidates
     * @param topN       how many to keep
     * @return at most topN candidates, best first, each carrying its rerank score
     */
    List<Merged> rerank(String query, List<Merged> candidates, int topN);

    /** Short model identifier, recorded in the trace. */
    String modelName();
}
