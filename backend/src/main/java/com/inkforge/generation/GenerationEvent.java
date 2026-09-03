package com.inkforge.generation;

import java.math.BigDecimal;

/** SSE events emitted by the continuation stream: token / done / error. */
public sealed interface GenerationEvent {

    record Token(String delta) implements GenerationEvent {
    }

    record Done(DoneMeta meta) implements GenerationEvent {
    }

    record Error(String message) implements GenerationEvent {
    }

    /** Metadata carried by the done event; also persisted as the GenerationLog. */
    record DoneMeta(String generationId, String provider, String model, int promptTokens,
                    int completionTokens, int totalTokens, long latencyMs, BigDecimal estimatedCostUsd,
                    Integer retrievedCount, String retrievalTraceId) {
    }
}
