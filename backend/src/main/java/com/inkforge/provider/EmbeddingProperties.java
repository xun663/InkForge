package com.inkforge.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding configuration. The DIMENSION invariant is supreme: configuration dimension
 * must equal Mock vector dimension = real provider dimension = PostgreSQL vector(N).
 * Any mismatch fails loudly — never silent truncation/padding.
 */
@ConfigurationProperties(prefix = "inkforge.embedding")
public record EmbeddingProperties(
        String provider,
        String model,
        String baseUrl,
        String apiKey,          // from environment only, never committed
        int dimension,
        int batchSize,
        int timeoutSeconds) {

    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "mock";
        }
        if (model == null || model.isBlank()) {
            model = "bge-m3";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.siliconflow.cn/v1";
        }
        if (dimension <= 0) {
            dimension = 1024; // must match Flyway schema: memory_chunk.embedding vector(1024)
        }
        if (batchSize <= 0) {
            batchSize = 16;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 120;
        }
    }
}
