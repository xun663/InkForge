package com.inkforge.memory;

import java.time.Instant;
import java.util.List;

/** A character of a novel: identity (name + aliases), appearance range, presence status. */
public record Character(
        String id,
        String novelId,
        String name,
        List<String> aliases,
        int firstChapter,
        int lastChapter,
        CharacterStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Character {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
