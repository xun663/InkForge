package com.inkforge.provider;

/**
 * One streaming event from an LLM provider: an incremental text delta, and — on the
 * final event — the authoritative usage reported by the provider (may be null if the
 * backend does not report usage during streaming).
 */
public record ProviderStreamEvent(String delta, LlmUsage usage) {

    public static ProviderStreamEvent delta(String text) {
        return new ProviderStreamEvent(text, null);
    }

    public static ProviderStreamEvent usage(LlmUsage usage) {
        return new ProviderStreamEvent("", usage);
    }
}
