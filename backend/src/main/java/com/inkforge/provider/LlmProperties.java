package com.inkforge.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for LLM access. The API key comes exclusively from environment
 * variables and is never stored in source code or committed config.
 */
@ConfigurationProperties(prefix = "inkforge.llm")
public record LlmProperties(String provider, String baseUrl, String apiKey, String model,
                            int timeoutSeconds, Mock mock) {

    public static final String DEFAULT_PROVIDER = "mock";

    public LlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = DEFAULT_PROVIDER;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 300;
        }
        if (mock == null) {
            mock = new Mock(0);
        }
    }

    /** Streaming delay per chunk for demo effect; tests use 0. */
    public record Mock(long delayMs) {
    }
}
