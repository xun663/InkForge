package com.inkforge.provider;

import reactor.core.publisher.Flux;

/**
 * Abstraction over LLM backends. Implementations cover OpenAI-compatible APIs
 * (DeepSeek / OpenAI / Ollama / any compatible endpoint) and a built-in Mock.
 * Core business code depends only on this interface, never on a specific vendor.
 */
public interface LlmProvider {

    /** Stable identifier used in GenerationLog, e.g. "mock", "deepseek", "openai-compatible". */
    String name();

    /** Default model used when the request does not specify one. */
    String defaultModel();

    /** Streams completion deltas; the final event carries the provider-reported usage. */
    Flux<ProviderStreamEvent> stream(LlmRequest request);

    /** Non-streaming completion (used by tests and internal callers). */
    LlmResponse complete(LlmRequest request);
}
