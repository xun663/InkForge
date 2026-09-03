package com.inkforge.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Selects the active EmbeddingProvider from configuration. Default is Mock —
 * zero API key, zero network, full pipeline (P3-B is untouched; P3-D consumes both
 * retrievers later).
 */
@Configuration
public class EmbeddingProviderConfig {

    @Bean
    EmbeddingProvider embeddingProvider(EmbeddingProperties properties, ObjectMapper objectMapper) {
        return switch (properties.provider().toLowerCase()) {
            case MockEmbeddingProvider.NAME -> new MockEmbeddingProvider(properties);
            case "openai-compatible", "openai", "siliconflow" -> {
                if (properties.apiKey() == null || properties.apiKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "Embedding provider '" + properties.provider()
                                    + "' 需要 INKFORGE_EMBEDDING_API_KEY（环境变量），或改用 mock provider");
                }
                yield new OpenAiCompatibleEmbeddingProvider(
                        properties.provider().toLowerCase(),
                        WebClient.builder().baseUrl(properties.baseUrl()).build(),
                        properties, objectMapper);
            }
            default -> throw new IllegalArgumentException(
                    "未知 inkforge.embedding.provider '" + properties.provider()
                            + "'。可选：mock / openai-compatible / openai / siliconflow");
        };
    }
}
