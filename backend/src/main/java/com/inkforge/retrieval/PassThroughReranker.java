package com.inkforge.retrieval;

import java.util.List;

/**
 * Default reranker: no LLM, keeps the fusion order, only caps at topK.
 * Deterministic and repeatable — the baseline for the P3-G ablation experiments.
 * Assembled by RerankerConfig (the single Reranker bean); not a @Component itself.
 */
public class PassThroughReranker implements Reranker {

    @Override
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        return candidates.stream().limit(topK).toList();
    }
}
