package com.inkforge.memory.extraction;

import java.util.List;

/** Structured output contract of one memory extraction call (single LLM round-trip). */
public record ChapterExtractionResult(
        ExtractedSummary summary,
        List<ExtractedCharacter> characters,
        List<ExtractedEvent> events) {

    public ChapterExtractionResult {
        characters = characters == null ? List.of() : List.copyOf(characters);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
