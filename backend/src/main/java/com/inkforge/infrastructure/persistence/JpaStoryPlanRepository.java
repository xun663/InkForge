package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.StoryPlanEntity;
import com.inkforge.planning.StoryPlan;
import com.inkforge.planning.StoryPlanRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL StoryPlan repository（"postgres" profile）。plan id 应用生成，
 * merge 提供 upsert。活跃计划唯一性由 V5 的 partial unique index 兜底。
 */
@Repository
@Profile("postgres")
@Transactional
public class JpaStoryPlanRepository implements StoryPlanRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public StoryPlan save(StoryPlan plan) {
        em.merge(PlanningMappers.toEntity(plan));
        return plan;
    }

    @Override
    public Optional<StoryPlan> findById(String planId) {
        return Optional.ofNullable(em.find(StoryPlanEntity.class, planId))
                .map(PlanningMappers::toDomain);
    }

    @Override
    public List<StoryPlan> findByNovelId(String novelId) {
        return em.createQuery(
                        "SELECT p FROM StoryPlanEntity p WHERE p.novelId = :novelId "
                                + "ORDER BY p.createdAt DESC", StoryPlanEntity.class)
                .setParameter("novelId", novelId)
                .getResultList().stream()
                .map(PlanningMappers::toDomain)
                .toList();
    }
}
