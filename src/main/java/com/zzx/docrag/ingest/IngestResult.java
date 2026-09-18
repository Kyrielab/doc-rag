package com.zzx.docrag.ingest;

/**
 * Outcome of one ingestion run. Stage timings are recorded because ingestion cost
 * is dominated by embedding calls, and that is the number worth optimizing.
 *
 * @param docId        document id
 * @param title        document title
 * @param chunkCount   number of chunks produced
 * @param parseMillis  time spent parsing the source file
 * @param chunkMillis  time spent chunking
 * @param embedMillis  time spent calling the embedding model
 * @param indexMillis  time spent writing to Elasticsearch
 * @param totalMillis  end-to-end wall clock
 * @param preview      first N characters of the first chunk, lets the UI confirm parsing worked
 */
public record IngestResult(
        String docId,
        String title,
        int chunkCount,
        long parseMillis,
        long chunkMillis,
        long embedMillis,
        long indexMillis,
        long totalMillis,
        String preview
) {
}
