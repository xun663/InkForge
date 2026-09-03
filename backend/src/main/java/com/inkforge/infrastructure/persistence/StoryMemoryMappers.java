package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.ChapterSummaryEntity;
import com.inkforge.infrastructure.persistence.entity.CharacterEntity;
import com.inkforge.infrastructure.persistence.entity.CharacterFactEntity;
import com.inkforge.infrastructure.persistence.entity.MemoryExtractionRecordEntity;
import com.inkforge.infrastructure.persistence.entity.NovelChapterId;
import com.inkforge.infrastructure.persistence.entity.StoryEventEntity;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.MemoryExtractionRecord;
import com.inkforge.memory.StoryEvent;

/**
 * Domain record ↔ JPA entity mapping for Story Memory. Pure static functions —
 * the domain stays immutable and annotation-free; entities stay persistence-only.
 */
public final class StoryMemoryMappers {

    private StoryMemoryMappers() {
    }

    public static ChapterSummaryEntity toEntity(ChapterSummary summary) {
        ChapterSummaryEntity entity = new ChapterSummaryEntity();
        entity.setId(new NovelChapterId(summary.novelId(), summary.chapterOrdinal()));
        entity.setSummary(summary.summary());
        entity.setKeyEvents(summary.keyEvents());
        entity.setCharacters(summary.characters());
        entity.setLocations(summary.locations());
        entity.setImportantItems(summary.importantItems());
        entity.setUnresolvedThreads(summary.unresolvedThreads());
        entity.setCreatedAt(summary.createdAt());
        return entity;
    }

    public static ChapterSummary toDomain(ChapterSummaryEntity entity) {
        return new ChapterSummary(
                entity.getId().getNovelId(), entity.getId().getOrdinal(),
                entity.getSummary(), entity.getKeyEvents(), entity.getCharacters(),
                entity.getLocations(), entity.getImportantItems(), entity.getUnresolvedThreads(),
                entity.getCreatedAt());
    }

    public static CharacterEntity toEntity(Character character) {
        CharacterEntity entity = new CharacterEntity();
        entity.setId(character.id());
        entity.setNovelId(character.novelId());
        entity.setName(character.name());
        entity.setAliases(character.aliases());
        entity.setFirstChapter(character.firstChapter());
        entity.setLastChapter(character.lastChapter());
        entity.setStatus(character.status());
        entity.setCreatedAt(character.createdAt());
        entity.setUpdatedAt(character.updatedAt());
        return entity;
    }

    public static Character toDomain(CharacterEntity entity) {
        return new Character(
                entity.getId(), entity.getNovelId(), entity.getName(), entity.getAliases(),
                entity.getFirstChapter(), entity.getLastChapter(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    public static CharacterFactEntity toEntity(CharacterFact fact) {
        CharacterFactEntity entity = new CharacterFactEntity();
        entity.setId(fact.id());
        entity.setCharacterId(fact.characterId());
        entity.setCategory(fact.category());
        entity.setAttribute(fact.attribute());
        entity.setValue(fact.value());
        entity.setTargetCharacter(fact.targetCharacter());
        entity.setStatus(fact.status());
        entity.setValidFromChapter(fact.validFromChapter());
        entity.setValidUntilChapter(fact.validUntilChapter());
        entity.setConfidence(fact.confidence());
        entity.setSourceChapter(fact.sourceChapter());
        entity.setSourceQuote(fact.sourceQuote());
        entity.setCreatedAt(fact.createdAt());
        entity.setUpdatedAt(fact.updatedAt());
        return entity;
    }

    public static CharacterFact toDomain(CharacterFactEntity entity) {
        return new CharacterFact(
                entity.getId(), entity.getCharacterId(), entity.getCategory(),
                entity.getAttribute(), entity.getValue(), entity.getTargetCharacter(),
                entity.getStatus(), entity.getValidFromChapter(), entity.getValidUntilChapter(),
                entity.getConfidence(), entity.getSourceChapter(), entity.getSourceQuote(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    public static StoryEventEntity toEntity(StoryEvent event) {
        StoryEventEntity entity = new StoryEventEntity();
        entity.setId(event.id());
        entity.setNovelId(event.novelId());
        entity.setChapterOrdinal(event.chapterOrdinal());
        entity.setTitle(event.title());
        entity.setDescription(event.description());
        entity.setParticipants(event.participants());
        entity.setLocation(event.location());
        entity.setConsequences(event.consequences());
        entity.setImportance(event.importance());
        entity.setSourceQuote(event.sourceQuote());
        entity.setCreatedAt(event.createdAt());
        return entity;
    }

    public static StoryEvent toDomain(StoryEventEntity entity) {
        return new StoryEvent(
                entity.getId(), entity.getNovelId(), entity.getChapterOrdinal(),
                entity.getTitle(), entity.getDescription(), entity.getParticipants(),
                entity.getLocation(), entity.getConsequences(), entity.getImportance(),
                entity.getSourceQuote(), entity.getCreatedAt());
    }

    public static MemoryExtractionRecordEntity toEntity(MemoryExtractionRecord record) {
        MemoryExtractionRecordEntity entity = new MemoryExtractionRecordEntity();
        entity.setId(new NovelChapterId(record.novelId(), record.chapterOrdinal()));
        entity.setStatus(record.status());
        entity.setErrorMessage(record.errorMessage());
        entity.setModel(record.model());
        entity.setStats(record.stats());
        entity.setCreatedAt(record.createdAt());
        return entity;
    }

    public static MemoryExtractionRecord toDomain(MemoryExtractionRecordEntity entity) {
        return new MemoryExtractionRecord(
                entity.getId().getNovelId(), entity.getId().getOrdinal(),
                entity.getStatus(), entity.getErrorMessage(), entity.getModel(),
                entity.getStats(), entity.getCreatedAt());
    }
}
