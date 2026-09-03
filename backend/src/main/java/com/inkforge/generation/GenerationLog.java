package com.inkforge.generation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Audit record for one LLM run. {@code generationId} correlates 1:1 with an SSE
 * {@code done} event for continuations; extraction runs are logged with
 * {@code type=EXTRACTION} for cost observability; planning runs use
 * {@code type=PLANNING}. {@code mode}/{@code planId} are P6 planning metadata
 * (null for legacy continuations and extractions).
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
        String mode,
        String planId,
        Instant createdAt) {

    /** P6 兼容构造器：不带规划字段的旧调用点（提取等）保持零改动。 */
    public GenerationLog(String generationId, String novelId, String provider, String model,
                         int promptTokens, int completionTokens, long latencyMs,
                         BigDecimal estimatedCostUsd, String status, String errorMessage,
                         String type, Instant createdAt) {
        this(generationId, novelId, provider, model, promptTokens, completionTokens, latencyMs,
                estimatedCostUsd, status, errorMessage, type, null, null, createdAt);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
