package com.zzx.docrag.rag;

import com.zzx.docrag.retrieve.RetrievalTrace;

import java.util.List;

/**
 * The full response of the question answering endpoint.
 *
 * <p>The trace travels with the answer on purpose. In an interview this is the artifact that
 * turns "I built a RAG app" into "here is the latency breakdown and the retriever contribution
 * for every query".
 *
 * @param answer          generated answer text
 * @param citations       sources cited by the answer
 * @param citationRefs    every marker found in the text, valid or not
 * @param refused         whether the model declined because the context was insufficient
 * @param trace           per-stage evidence
 * @param answerChars     answer length, a cheap proxy for generation cost
 * @param citationAccuracy fraction of markers that resolved to a real chunk
 */
public record QaAnswer(
        String answer,
        List<Citation> citations,
        List<CitationRef> citationRefs,
        boolean refused,
        RetrievalTrace trace,
        int answerChars,
        double citationAccuracy
) {
    public static QaAnswer of(String answer,
                              List<Citation> citations,
                              List<CitationRef> citationRefs,
                              boolean refused,
                              RetrievalTrace trace) {
        long total = citationRefs.size();
        long valid = citationRefs.stream().filter(CitationRef::valid).count();
        double accuracy = total == 0 ? 1.0 : (double) valid / total;
        return new QaAnswer(answer, citations, citationRefs, refused, trace, answer.length(), accuracy);
    }
}
