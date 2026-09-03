package com.inkforge.memory;

import java.time.Instant;

/**
 * Record of one extraction run for one chapter (latest run wins per chapter).
 * Process observability — separated from ChapterSummary, which holds story content only.
 */
public record MemoryExtractionRecord(
        String novelId,
        int chapterOrdinal,
        String status,              // SUCCESS / FAILED
        String errorMessage,        // parse/validation error on FAILED
        String model,
        MemoryExtractionStats stats,
        Instant createdAt) {

    public boolean succeeded() {
        return "SUCCESS".equals(status);
    }
}
