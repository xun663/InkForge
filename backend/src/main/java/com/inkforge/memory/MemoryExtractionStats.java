package com.inkforge.memory;

import com.inkforge.provider.LlmUsage;

/**
 * Observability data of ONE extraction run — deliberately separate from
 * {@link ChapterSummary} (story content) and from {@link MemoryExtractionRecord}
 * (which adds status/model and anchors this to a chapter).
 *
 * <p>Feeds: unit tests, debug, the frontend memory panel, and the future
 * memory-quality evaluation (quote validation rate, conflict rate, …).
 */
public record MemoryExtractionStats(
        int charactersExtracted,
        int factsExtracted,
        int eventsExtracted,
        int quotesValidated,
        int quotesRejected,
        int retries,
        long durationMs,
        LlmUsage tokenUsage) {   // nullable when the provider reports no usage

    public MemoryExtractionStats {
        tokenUsage = tokenUsage == null
                ? new LlmUsage(0, 0)
                : tokenUsage;
    }
}
