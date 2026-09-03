package com.inkforge.memory;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for Story Memory. Phase 2 uses an in-memory implementation;
 * Phase 3 replaces it with JPA + PostgreSQL (pgvector stays downstream of this
 * port as a denormalized retrieval view).
 */
public interface StoryMemoryRepository {

    // --- chapter summaries ---
    void saveSummary(ChapterSummary summary);

    Optional<ChapterSummary> findSummary(String novelId, int chapterOrdinal);

    List<ChapterSummary> findSummaries(String novelId, int fromOrdinal, int toOrdinal);

    // --- characters ---
    Character saveCharacter(Character character);

    /** Exact match on name or any alias (normalized: whitespace removed). */
    Optional<Character> findCharacterByName(String novelId, String name);

    Optional<Character> findCharacterById(String characterId);

    List<Character> findCharacters(String novelId);

    // --- facts ---
    void saveFact(CharacterFact fact);

    /** All facts of a character across all lifecycles (current, superseded, uncertain). */
    List<CharacterFact> findFacts(String characterId);

    /** Facts currently in effect: status CURRENT. */
    List<CharacterFact> findCurrentFacts(String characterId);

    // --- events ---
    void saveEvent(StoryEvent event);

    List<StoryEvent> findEvents(String novelId, int limit, boolean recentFirst);

    // --- extraction records ---
    void saveExtractionRecord(MemoryExtractionRecord record);

    Optional<MemoryExtractionRecord> findExtractionRecord(String novelId, int chapterOrdinal);

    List<MemoryExtractionRecord> findExtractionRecords(String novelId);
}
