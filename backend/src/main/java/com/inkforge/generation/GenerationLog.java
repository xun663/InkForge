package com.inkforge.generation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Audit record for one LLM run. {@code generationId} correlates 1:1 with an SSE
 * {@code done} event for continuations; extraction runs are logged with
 * {@code type=EXTRACTION} for cost observability.
 */
public record GenerationLog(
        String generationId,
        String novelId,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        BigDecimal estimatedCostUsd,
        String status,
        String errorMessage,
        String type,
        Instant createdAt) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
