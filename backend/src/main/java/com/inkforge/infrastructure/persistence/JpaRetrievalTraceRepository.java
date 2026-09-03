package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.RetrievalTraceEntity;
import com.inkforge.retrieval.RetrievalResult;
import com.inkforge.retrieval.RetrievalTrace;
import com.inkforge.retrieval.RetrievalTraceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL implementation of the retrieval trace port ("postgres" profile). */
@Repository
@Profile("postgres")
@Transactional
public class JpaRetrievalTraceRepository implements RetrievalTraceRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void save(RetrievalTrace trace) {
        em.persist(toEntity(trace));
    }

    @Override
    public Optional<RetrievalTrace> findById(String traceId) {
        return Optional.ofNullable(em.find(RetrievalTraceEntity.class, traceId))
                .map(JpaRetrievalTraceRepository::toDomain);
    }

    @Override
    public List<RetrievalTrace> findByNovelId(String novelId, int limit) {
        return em.createQuery("SELECT t FROM RetrievalTraceEntity t WHERE t.novelId = :novelId "
                                + "ORDER BY t.createdAt DESC", RetrievalTraceEntity.class)
                .setParameter("novelId", novelId)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(JpaRetrievalTraceRepository::toDomain)
                .toList();
    }

    private static RetrievalTraceEntity toEntity(RetrievalTrace trace) {
        RetrievalTraceEntity entity = new RetrievalTraceEntity();
        entity.setId(trace.id());
        entity.setNovelId(trace.novelId());
        entity.setGenerationId(trace.generationId());
        entity.setQueries(trace.queries());
        entity.setPipeline(trace.pipeline());
        entity.setCreatedAt(trace.createdAt());
        return entity;
    }

    private static RetrievalTrace toDomain(RetrievalTraceEntity entity) {
        Map<String, List<RetrievalResult>> pipeline = entity.getPipeline();
        return new RetrievalTrace(entity.getId(), entity.getNovelId(), entity.getGenerationId(),
                entity.getQueries(), pipeline, entity.getCreatedAt() == null ? Instant.now() : entity.getCreatedAt());
    }
}
