package com.inkforge.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2 in-memory Story Memory (accepted limitation, see docs/architecture.md).
 * Indexes: (novelId, name|alias) → character; (novelId, ordinal) → summary / extraction record.
 */
@Component
public class InMemoryStoryMemoryRepository implements StoryMemoryRepository {

    private final Map<String, ChapterSummary> summaries = new ConcurrentHashMap<>();
    private final Map<String, Character> characters = new ConcurrentHashMap<>();
    private final Map<String, List<CharacterFact>> factsByCharacter = new ConcurrentHashMap<>();
    private final Map<String, List<StoryEvent>> eventsByNovel = new ConcurrentHashMap<>();
    private final Map<String, MemoryExtractionRecord> extractionRecords = new ConcurrentHashMap<>();

    @Override
    public void saveSummary(ChapterSummary summary) {
        summaries.put(key(summary.novelId(), summary.chapterOrdinal()), summary);
    }

    @Override
    public Optional<ChapterSummary> findSummary(String novelId, int chapterOrdinal) {
        return Optional.ofNullable(summaries.get(key(novelId, chapterOrdinal)));
    }

    @Override
    public List<ChapterSummary> findSummaries(String novelId, int fromOrdinal, int toOrdinal) {
        return summaries.values().stream()
                .filter(s -> s.novelId().equals(novelId))
                .filter(s -> s.chapterOrdinal() >= fromOrdinal && s.chapterOrdinal() <= toOrdinal)
                .sorted(Comparator.comparingInt(ChapterSummary::chapterOrdinal))
                .toList();
    }

    @Override
    public Character saveCharacter(Character character) {
        characters.put(character.id(), character);
        return character;
    }

    @Override
    public Optional<Character> findCharacterByName(String novelId, String name) {
        String normalized = normalizeName(name);
        return characters.values().stream()
                .filter(c -> c.novelId().equals(novelId))
                .filter(c -> normalizeName(c.name()).equals(normalized)
                        || c.aliases().stream().anyMatch(a -> normalizeName(a).equals(normalized)))
                .findFirst();
    }

    @Override
    public Optional<Character> findCharacterById(String characterId) {
        return Optional.ofNullable(characters.get(characterId));
    }

    @Override
    public List<Character> findCharacters(String novelId) {
        return characters.values().stream()
                .filter(c -> c.novelId().equals(novelId))
                .sorted(Comparator.comparingInt(Character::firstChapter))
                .toList();
    }

    @Override
    public void saveFact(CharacterFact fact) {
        // upsert by id (same semantics as JPA merge): superseding a fact replaces it in place,
        // never leaves a stale copy behind
        synchronized (factsByCharacter) {
            List<CharacterFact> facts = factsByCharacter
                    .computeIfAbsent(fact.characterId(), k -> new ArrayList<>());
            facts.removeIf(f -> f.id().equals(fact.id()));
            facts.add(fact);
        }
    }

    @Override
    public List<CharacterFact> findFacts(String characterId) {
        return List.copyOf(factsByCharacter.getOrDefault(characterId, List.of()));
    }

    @Override
    public List<CharacterFact> findCurrentFacts(String characterId) {
        return factsByCharacter.getOrDefault(characterId, List.of()).stream()
                .filter(f -> f.status() == FactStatus.CURRENT)
                .toList();
    }

    @Override
    public void saveEvent(StoryEvent event) {
        synchronized (eventsByNovel) {
            eventsByNovel.computeIfAbsent(event.novelId(), k -> new ArrayList<>()).add(event);
        }
    }

    @Override
    public List<StoryEvent> findEvents(String novelId, int limit, boolean recentFirst) {
        return eventsByNovel.getOrDefault(novelId, List.of()).stream()
                .sorted(recentFirst
                        ? Comparator.comparingInt(StoryEvent::chapterOrdinal).reversed()
                        : Comparator.comparingInt(StoryEvent::chapterOrdinal))
                .limit(limit)
                .toList();
    }

    @Override
    public void saveExtractionRecord(MemoryExtractionRecord record) {
        extractionRecords.put(key(record.novelId(), record.chapterOrdinal()), record);
    }

    @Override
    public Optional<MemoryExtractionRecord> findExtractionRecord(String novelId, int chapterOrdinal) {
        return Optional.ofNullable(extractionRecords.get(key(novelId, chapterOrdinal)));
    }

    @Override
    public List<MemoryExtractionRecord> findExtractionRecords(String novelId) {
        return extractionRecords.values().stream()
                .filter(r -> r.novelId().equals(novelId))
                .sorted(Comparator.comparingInt(MemoryExtractionRecord::chapterOrdinal))
                .toList();
    }

    private static String key(String novelId, int chapterOrdinal) {
        return novelId + ":" + chapterOrdinal;
    }

    /** Chinese names carry no internal whitespace; removing it merges 林 默 → 林默 safely. */
    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").trim();
    }
}
