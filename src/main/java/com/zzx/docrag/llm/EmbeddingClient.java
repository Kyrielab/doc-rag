package com.zzx.docrag.llm;

/**
 * Embedding port. Kept as a one-method interface so that a local model
 * (bge-m3 via Ollama / ONNX) can be swapped in without touching retrieval code.
 */
public interface EmbeddingClient {

    /** Returns the embedding of a single text. */
    float[] embed(String text);

    /** Model identifier actually in use, reported by the health endpoint. */
    String modelName();

    /** Vector dimension, must equal the mapping dimension. */
    int dimension();
}
