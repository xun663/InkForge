package com.inkforge.retrieval;

import java.util.List;

/**
 * Re-ranks fused candidates. Implementations: PassThrough (default, deterministic,
 * zero-LLM) and LlmListwise (optional, configured). A rerank failure must never break
 * the continuation pipeline — callers degrade to the fusion ranking.
 */
public interface Reranker {

    /**
     * @param query      the retrieval query
     * @param candidates fused candidates (fusion order)
     * @param topK       result cap
     * @return re-ranked candidates, capped at topK; original RetrievalResults are
     *         returned as-is (never text-modified)
     */
    List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK);
}
