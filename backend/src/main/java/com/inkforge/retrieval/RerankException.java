package com.inkforge.retrieval;

/**
 * Raised when an LLM listwise rerank fails protocol validation (invalid/duplicate/
 * missing numbers, unparseable JSON). Deliberately NOT swallowed inside the reranker —
 * HybridRetrievalService degrades to the fusion ranking.
 */
public class RerankException extends RuntimeException {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
