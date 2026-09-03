package com.inkforge.retrieval;

import java.util.List;

/**
 * Unified retrieval port over MemoryChunks. Implementations: Lucene BM25 (P3-B),
 * Vector (P3-C). Retrieval must NEVER be a hard dependency of continuation —
 * callers treat exceptions/empty results as "no retrieved memory".
 */
public interface MemoryRetriever {

    /**
     * @param novelId novel-scoped search (results from other novels must never leak in)
     * @param query   free-text query (Chinese web-novel vocabulary)
     * @param topK    result count cap
     */
    List<RetrievalResult> retrieve(String novelId, String query, int topK);
}
