package com.inkforge.provider;

import java.util.List;

/**
 * Abstraction over embedding backends. Implementations: deterministic Mock (zero-key,
 * pipeline correctness only — NOT semantic quality) and OpenAI-compatible APIs
 * (bge-m3 or any configurable Chinese/multilingual model). The embedding model,
 * base-url, api-key and dimension are configuration, never hardcoded.
 */
public interface EmbeddingProvider {

    /** Provider identifier for logs, e.g. "mock", "openai-compatible". */
    String name();

    Embedding embed(String text);

    /**
     * Batch embedding, result order matches the input order. Providers that do not
     * support true batching split internally — upper layers never see vendor limits.
     */
    List<Embedding> embedBatch(List<String> texts);
}
