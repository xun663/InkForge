package com.inkforge.memory;

import java.time.Instant;
import java.util.List;

/**
 * Structured story memory of one chapter — PURE story content.
 * Process observability (stats, status, model) lives in {@link MemoryExtractionRecord},
 * deliberately separated from this record.
 */
public record ChapterSummary(
        String novelId,
        int chapterOrdinal,
        String summary,
        List<String> keyEvents,
        List<SummaryCharacter> characters,
        List<String> locations,
        List<String> importantItems,
        List<String> unresolvedThreads,
        Instant createdAt) {

    public ChapterSummary {
        keyEvents = keyEvents == null ? List.of() : List.copyOf(keyEvents);
        characters = characters == null ? List.of() : List.copyOf(characters);
        locations = locations == null ? List.of() : List.copyOf(locations);
        importantItems = importantItems == null ? List.of() : List.copyOf(importantItems);
        unresolvedThreads = unresolvedThreads == null ? List.of() : List.copyOf(unresolvedThreads);
    }
}
