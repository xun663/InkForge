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
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.MemoryExtractionRecord;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of the Story Memory port ("postgres" profile).
 * All entity IDs are application-assigned, so {@code merge} provides the same
 * upsert-by-id semantics as the in-memory store.
 */
@Repository
@Profile("postgres")
@Transactional
public class JpaStoryMemoryRepository implements StoryMemoryRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void saveSummary(ChapterSummary summary) {
        em.merge(StoryMemoryMappers.toEntity(summary));
    }

    @Override
    public Optional<ChapterSummary> findSummary(String novelId, int chapterOrdinal) {
        ChapterSummaryEntity entity = em.find(ChapterSummaryEntity.class, new NovelChapterId(novelId, chapterOrdinal));
        return Optional.ofNullable(entity).map(StoryMemoryMappers::toDomain);
    }

    @Override
    public List<ChapterSummary> findSummaries(String novelId, int fromOrdinal, int toOrdinal) {
        return em.createQuery(
                        "SELECT s FROM ChapterSummaryEntity s WHERE s.id.novelId = :novelId "
                                + "AND s.id.ordinal BETWEEN :from AND :to ORDER BY s.id.ordinal",
                        ChapterSummaryEntity.class)
                .setParameter("novelId", novelId)
                .setParameter("from", fromOrdinal)
                .setParameter("to", toOrdinal)
                .getResultList().stream()
                .map(StoryMemoryMappers::toDomain)
                .toList();
    }

    @Override
    public Character saveCharacter(Character character) {
        return StoryMemoryMappers.toDomain(em.merge(StoryMemoryMappers.toEntity(character)));
    }

    @Override
    public Optional<Character> findCharacterByName(String novelId, String name) {
        String normalized = normalizeName(name);
        return em.createQuery("SELECT c FROM CharacterEntity c WHERE c.novelId = :novelId ORDER BY c.firstChapter",
                        CharacterEntity.class)
                .setParameter("novelId", novelId)
                .getResultList().stream()
                .filter(c -> normalizeName(c.getName()).equals(normalized)
                        || c.getAliases() != null && c.getAliases().stream()
                        .anyMatch(a -> normalizeName(a).equals(normalized)))
                .findFirst()
                .map(StoryMemoryMappers::toDomain);
    }

    @Override
    public Optional<Character> findCharacterById(String characterId) {
        return Optional.ofNullable(em.find(CharacterEntity.class, characterId))
                .map(StoryMemoryMappers::toDomain);
    }

    @Override
    public List<Character> findCharacters(String novelId) {
        return em.createQuery("SELECT c FROM CharacterEntity c WHERE c.novelId = :novelId ORDER BY c.firstChapter",
                        CharacterEntity.class)
                .setParameter("novelId", novelId)
                .getResultList().stream()
                .map(StoryMemoryMappers::toDomain)
                .toList();
    }

    @Override
    public void saveFact(CharacterFact fact) {
        em.merge(StoryMemoryMappers.toEntity(fact));
    }

    @Override
    public List<CharacterFact> findFacts(String characterId) {
        return em.createQuery("SELECT f FROM CharacterFactEntity f WHERE f.characterId = :cid",
                        CharacterFactEntity.class)
                .setParameter("cid", characterId)
                .getResultList().stream()
                .map(StoryMemoryMappers::toDomain)
                .toList();
    }

    @Override
    public List<CharacterFact> findCurrentFacts(String characterId) {
        return em.createQuery("SELECT f FROM CharacterFactEntity f WHERE f.characterId = :cid AND f.status = :status",
                        CharacterFactEntity.class)
                .setParameter("cid", characterId)
                .setParameter("status", FactStatus.CURRENT)
                .getResultList().stream()
                .map(StoryMemoryMappers::toDomain)
                .toList();
    }

    @Override
    public void saveEvent(StoryEvent event) {
        em.merge(StoryMemoryMappers.toEntity(event));
    }

    @Override
    public List<StoryEvent> findEvents(String novelId, int limit, boolean recentFirst) {
        String order = recentFirst ? "DESC" : "ASC";
        return em.createQuery("SELECT e FROM StoryEventEntity e WHERE e.novelId = :novelId "
                                + "ORDER BY e.chapterOrdinal " + order, StoryEventEntity.class)
                .setParameter("novelId", novelId)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(StoryMemoryMappers::toDomain)
                .sorted(recentFirst
                        ? Comparator.comparingInt(StoryEvent::chapterOrdinal).reversed()
                        : Comparator.comparingInt(StoryEvent::chapterOrdinal))
                .toList();
    }

    @Override
    public void saveExtractionRecord(MemoryExtractionRecord record) {
        em.merge(StoryMemoryMappers.toEntity(record));
    }

    @Override
    public Optional<MemoryExtractionRecord> findExtractionRecord(String novelId, int chapterOrdinal) {
        MemoryExtractionRecordEntity entity =
                em.find(MemoryExtractionRecordEntity.class, new NovelChapterId(novelId, chapterOrdinal));
        return Optional.ofNullable(entity).map(StoryMemoryMappers::toDomain);
    }

    @Override
    public List<MemoryExtractionRecord> findExtractionRecords(String novelId) {
        return em.createQuery(
                        "SELECT r FROM MemoryExtractionRecordEntity r WHERE r.id.novelId = :novelId ORDER BY r.id.ordinal",
                        MemoryExtractionRecordEntity.class)
                .setParameter("novelId", novelId)
                .getResultList().stream()
                .map(StoryMemoryMappers::toDomain)
                .toList();
    }

    /** Same normalization as the in-memory store: Chinese names carry no internal whitespace. */
    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").trim();
    }
}
