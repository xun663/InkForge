package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.MemoryChunkEntity;
import com.inkforge.retrieval.MemoryChunk;
import com.inkforge.retrieval.MemoryChunkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** PostgreSQL implementation of the retrieval-chunk port ("postgres" profile). */
@Repository
@Profile("postgres")
@Transactional
public class JpaMemoryChunkRepository implements MemoryChunkRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<MemoryChunk> findByNovelId(String novelId) {
        return em.createQuery("SELECT c FROM MemoryChunkEntity c WHERE c.novelId = :novelId",
                        MemoryChunkEntity.class)
                .setParameter("novelId", novelId)
                .getResultList().stream()
                .map(JpaMemoryChunkRepository::toDomain)
                .toList();
    }

    @Override
    public List<MemoryChunk> findByNovelIdAndChapter(String novelId, int chapterOrdinal) {
        return em.createQuery(
                        "SELECT c FROM MemoryChunkEntity c WHERE c.novelId = :novelId AND c.chapterOrdinal = :ordinal",
                        MemoryChunkEntity.class)
                .setParameter("novelId", novelId)
                .setParameter("ordinal", chapterOrdinal)
                .getResultList().stream()
                .map(JpaMemoryChunkRepository::toDomain)
                .toList();
    }

    @Override
    public void replaceForChapter(String novelId, int chapterOrdinal, List<MemoryChunk> chunks) {
        em.createQuery("DELETE FROM MemoryChunkEntity c WHERE c.novelId = :novelId AND c.chapterOrdinal = :ordinal")
                .setParameter("novelId", novelId)
                .setParameter("ordinal", chapterOrdinal)
                .executeUpdate();
        for (MemoryChunk chunk : chunks) {
            em.persist(toEntity(chunk));
        }
    }

    @Override
    public void deleteByNovelId(String novelId) {
        em.createQuery("DELETE FROM MemoryChunkEntity c WHERE c.novelId = :novelId")
                .setParameter("novelId", novelId)
                .executeUpdate();
    }

    @Override
    public long revision(String novelId) {
        // row count + max(created_at) millis: bumps on every replace/delete with millisecond
        // precision. P3-E integration may move to an explicit version column if needed.
        Object[] row = em.createQuery(
                        "SELECT COUNT(c), COALESCE(MAX(c.createdAt), :epoch) FROM MemoryChunkEntity c "
                                + "WHERE c.novelId = :novelId", Object[].class)
                .setParameter("novelId", novelId)
                .setParameter("epoch", Instant.EPOCH)
                .getSingleResult();
        long count = (Long) row[0];
        Instant maxCreated = (Instant) row[1];
        return count * 1_000_000_000L + maxCreated.toEpochMilli();
    }

    private static MemoryChunkEntity toEntity(MemoryChunk chunk) {
        MemoryChunkEntity entity = new MemoryChunkEntity();
        entity.setId(chunk.id());
        entity.setNovelId(chunk.novelId());
        entity.setMemoryType(chunk.memoryType());
        entity.setSourceId(chunk.sourceId());
        entity.setChapterOrdinal(chunk.chapterOrdinal());
        entity.setText(chunk.text());
        entity.setSearchText(chunk.searchText());
        entity.setCreatedAt(chunk.createdAt());
        return entity;
    }

    private static MemoryChunk toDomain(MemoryChunkEntity entity) {
        return new MemoryChunk(
                entity.getId(), entity.getNovelId(), entity.getMemoryType(), entity.getSourceId(),
                entity.getChapterOrdinal(), entity.getText(), entity.getSearchText(),
                entity.getCreatedAt());
    }
}
