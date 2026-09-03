package com.inkforge.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hybrid retrieval pipeline configuration. All defaults are conservative and
 * deliberately not tuned without benchmark data.
 */
@ConfigurationProperties(prefix = "inkforge.retrieval")
public record RetrievalProperties(
        int bm25TopK,
        int vectorTopK,
        int fusionTopK,
        int rerankTopK,
        int rrfK,
        String reranker,           // "passthrough" (default) | "llm"
        int rerankMaxCandidates,   // candidates entering the LLM prompt (≤ this)
        int rerankCandidateMaxChars) {

    public RetrievalProperties {
        if (bm25TopK <= 0) {
            bm25TopK = 30;
        }
        if (vectorTopK <= 0) {
            vectorTopK = 30;
        }
        if (fusionTopK <= 0) {
            fusionTopK = 30;
        }
        if (rerankTopK <= 0) {
            rerankTopK = 30;
        }
        if (rrfK <= 0) {
            rrfK = 60;
        }
        if (reranker == null || reranker.isBlank()) {
            reranker = "passthrough";
        }
        if (rerankMaxCandidates <= 0) {
            rerankMaxCandidates = 15;
        }
        if (rerankCandidateMaxChars <= 0) {
            rerankCandidateMaxChars = 200;
        }
    }
}
