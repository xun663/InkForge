package com.inkforge.retrieval;

import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.provider.LlmProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Selects the active Reranker. Default is PassThrough (deterministic, zero-LLM —
 * the stable baseline for benchmarks). {@code inkforge.retrieval.reranker=llm}
 * switches to the LLM listwise reranker (works with the Mock provider too).
 */
@Configuration
public class RerankerConfig {

    @Bean
    Reranker reranker(RetrievalProperties properties, LlmProvider llmProvider,
                      PromptCatalog promptCatalog, ObjectMapper objectMapper) {
        if ("llm".equalsIgnoreCase(properties.reranker())) {
            return new LlmListwiseReranker(llmProvider, promptCatalog, properties, objectMapper);
        }
        return new PassThroughReranker(); // 唯一的 Reranker bean
    }
}
