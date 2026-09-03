package com.inkforge.memory;

import java.time.Instant;
import java.util.List;

/**
 * A plot event (v1). Deliberately has NO previous/next links — the event graph
 * (a DAG, not a chain) is a Phase 6 concern.
 */
public record StoryEvent(
        String id,
        String novelId,
        int chapterOrdinal,
        String title,
        String description,
        List<String> participants,   // character names, resolved best-effort at read time
        String location,
        List<String> consequences,
        int importance,              // 1-5
        String sourceQuote,
        Instant createdAt) {

    public StoryEvent {
        participants = participants == null ? List.of() : List.copyOf(participants);
        consequences = consequences == null ? List.of() : List.copyOf(consequences);
        importance = Math.max(1, Math.min(5, importance));
    }
}
