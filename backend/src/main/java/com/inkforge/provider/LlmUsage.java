package com.inkforge.provider;

/** Token usage reported by the provider — the authoritative numbers for logs and cost. */
public record LlmUsage(int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
