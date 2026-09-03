package com.inkforge.retrieval;

import java.util.List;

/**
 * Result of the retrieval step for a continuation: the memory to inject into the
 * context plus the trace id for explainability. Empty results / null traceId mean
 * "no retrieved memory" — the continuation falls back to P2 memory context.
 */
public record RetrievedMemory(List<RetrievalResult> results, String traceId) {

    public RetrievedMemory {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static RetrievedMemory empty() {
        return new RetrievedMemory(List.of(), null);
    }
}
