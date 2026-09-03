package com.inkforge.config;

import org.springframework.stereotype.Component;

/**
 * Mutable runtime LLM configuration (Runtime Configuration, 仅 LLM).
 *
 * <p>Startup values are seeded from {@code inkforge.llm.*} (env / application.yml) by
 * {@code LlmProviderConfig}; the config API can override them at runtime. The API key is
 * held IN MEMORY ONLY — never persisted, never logged, never serialized in responses.
 * A backend restart resets to the env/yml values.
 */
@Component
public class RuntimeLlmConfig {

    private volatile String provider;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String model;
    private volatile int timeoutSeconds;

    /** Immutable snapshot used to route a single LLM call. */
    public record Snapshot(String provider, String baseUrl, String apiKey, String model, int timeoutSeconds) {
    }

    /** Seed from env/yml-bound properties (called once at startup). */
    public void init(String provider, String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Apply a runtime update.
     * {@code null} = keep current; blank = clear (baseUrl/model/apiKey).
     * provider is normalized to lowercase.
     */
    public synchronized void update(String provider, String baseUrl, String model, String apiKey) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider.trim().toLowerCase();
        }
        if (baseUrl != null) {
            this.baseUrl = baseUrl.trim();
        }
        if (model != null) {
            this.model = model.trim();
        }
        if (apiKey != null) {
            this.apiKey = apiKey;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(provider, baseUrl, apiKey, model, timeoutSeconds);
    }
}
