package com.inkforge.provider;

import tools.jackson.databind.ObjectMapper;
import com.inkforge.common.TokenCounter;
import com.inkforge.config.RuntimeLlmConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the runtime-switchable {@link LlmProvider}.
 *
 * <p>Startup behavior is preserved: the env/yml-selected provider (default {@code mock})
 * seeds {@link RuntimeLlmConfig}; the same fail-fast guard for a non-mock provider
 * without a key still runs. The returned bean is a {@link DelegatingLlmProvider} that
 * routes every call to the current runtime config, so callers are unchanged and the
 * default remains mock (zero key, behavior-equivalent).
 */
@Configuration
public class LlmProviderConfig {

    @Bean
    LlmProvider llmProvider(RuntimeLlmConfig runtimeConfig, LlmProperties properties,
                            TokenCounter tokenCounter, ObjectMapper objectMapper) {
        String provider = properties.provider() == null ? "" : properties.provider().toLowerCase();
        if (!DelegatingLlmProvider.SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException(
                    "Unknown inkforge.llm.provider '" + properties.provider()
                            + "'. Expected one of: mock, deepseek, openai, ollama, openai-compatible.");
        }
        boolean local = "ollama".equalsIgnoreCase(provider);
        if (!local && !MockLlmProvider.NAME.equals(provider)
                && (properties.apiKey() == null || properties.apiKey().isBlank())) {
            throw new IllegalArgumentException(
                    "Provider '" + provider + "' requires INKFORGE_LLM_API_KEY. "
                            + "Set the environment variable, or switch to the mock provider.");
        }
        runtimeConfig.init(provider, properties.baseUrl(), properties.apiKey(),
                properties.model(), properties.timeoutSeconds());
        MockLlmProvider mockProvider = new MockLlmProvider(tokenCounter, objectMapper, properties);
        return new DelegatingLlmProvider(runtimeConfig, mockProvider, objectMapper);
    }
}
