package com.inkforge.retrieval;

/**
 * One deterministic retrieval query. Types: primary / character / thread.
 * Queries are built by {@link RetrievalQueryBuilder} — pure Java, no LLM, no randomness.
 */
public record RetrievalQuery(String type, String text) {
}
