package com.inkforge.infrastructure.persistence;

import com.inkforge.generation.GenerationLog;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.infrastructure.persistence.entity.GenerationLogEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** PostgreSQL implementation of the generation log port ("postgres" profile). */
@Repository
@Profile("postgres")
@Transactional
public class JpaGenerationLogRepository implements GenerationLogRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void save(GenerationLog log) {
        em.merge(GenerationLogMappers.toEntity(log));
    }

    @Override
    public Optional<GenerationLog> findById(String generationId) {
        return Optional.ofNullable(em.find(GenerationLogEntity.class, generationId))
                .map(GenerationLogMappers::toDomain);
    }

    @Override
    public List<GenerationLog> findByNovelId(String novelId) {
        return em.createQuery(
                        "SELECT g FROM GenerationLogEntity g WHERE g.novelId = :novelId ORDER BY g.createdAt",
                        GenerationLogEntity.class)
                .setParameter("novelId", novelId)
                .getResultList().stream()
                .map(GenerationLogMappers::toDomain)
                .toList();
    }
}
