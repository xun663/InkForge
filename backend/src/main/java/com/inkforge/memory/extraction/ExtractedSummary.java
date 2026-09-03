package com.inkforge.memory.extraction;

import java.util.List;

public record ExtractedSummary(
        String summary,
        List<String> keyEvents,
        List<ExtractedSummaryCharacter> characters,
        List<String> locations,
        List<String> importantItems,
        List<String> unresolvedThreads) {

    public ExtractedSummary {
        keyEvents = keyEvents == null ? List.of() : List.copyOf(keyEvents);
        characters = characters == null ? List.of() : List.copyOf(characters);
        locations = locations == null ? List.of() : List.copyOf(locations);
        importantItems = importantItems == null ? List.of() : List.copyOf(importantItems);
        unresolvedThreads = unresolvedThreads == null ? List.of() : List.copyOf(unresolvedThreads);
    }
}
