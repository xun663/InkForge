package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.PlotThreadEntity;
import com.inkforge.planning.PlotThread;
import com.inkforge.planning.PlotThreadRepository;
import com.inkforge.planning.PlotThreadStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL PlotThread repository（"postgres" profile）。
 * upsert 匹配键 title_normalized；uq_plot_thread_novel_title 唯一索引并发兜底。
 */
@Repository
@Profile("postgres")
@Transactional
public class JpaPlotThreadRepository implements PlotThreadRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public PlotThread save(PlotThread thread) {
        em.merge(PlanningMappers.toEntity(thread));
        return thread;
    }

    @Override
    public Optional<PlotThread> findById(String id) {
        return Optional.ofNullable(em.find(PlotThreadEntity.class, id))
                .map(PlanningMappers::toDomain);
    }

    @Override
    public Optional<PlotThread> findByTitle(String novelId, String normalizedTitle) {
        var list = em.createQuery(
                        "SELECT t FROM PlotThreadEntity t WHERE t.novelId = :novelId "
                                + "AND t.titleNormalized = :title", PlotThreadEntity.class)
                .setParameter("novelId", novelId)
                .setParameter("title", normalizedTitle)
                .setMaxResults(1)
                .getResultList();
        return list.stream().findFirst().map(PlanningMappers::toDomain);
    }

    @Override
    public List<PlotThread> findByNovelId(String novelId) {
        return em.createQuery(
                        "SELECT t FROM PlotThreadEntity t WHERE t.novelId = :novelId "
                                + "ORDER BY t.createdAt ASC", PlotThreadEntity.class)
                .setParameter("novelId", novelId)
                .getResultList().stream()
                .map(PlanningMappers::toDomain)
                .toList();
    }

    @Override
    public List<PlotThread> findOpenByNovelId(String novelId) {
        return em.createQuery(
                        "SELECT t FROM PlotThreadEntity t WHERE t.novelId = :novelId "
                                + "AND t.status = :status ORDER BY t.createdAt ASC", PlotThreadEntity.class)
                .setParameter("novelId", novelId)
                .setParameter("status", PlotThreadStatus.OPEN.name())
                .getResultList().stream()
                .map(PlanningMappers::toDomain)
                .toList();
    }
}
