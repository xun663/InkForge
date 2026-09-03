package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.MemoryBuildJobEntity;
import com.inkforge.memory.build.MemoryBuildJob;
import com.inkforge.memory.build.MemoryBuildJobRepository;
import com.inkforge.memory.build.MemoryBuildStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * PostgreSQL MemoryBuildJob repository（"postgres" profile）。Job id 应用生成，
 * 用 merge 提供 upsert；状态持久化，重启后可恢复。
 */
@Repository
@Profile("postgres")
@Transactional
public class JpaMemoryBuildJobRepository implements MemoryBuildJobRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public MemoryBuildJob save(MemoryBuildJob job) {
        em.merge(toEntity(job));
        return job;
    }

    @Override
    public Optional<MemoryBuildJob> findById(String jobId) {
        return Optional.ofNullable(em.find(MemoryBuildJobEntity.class, jobId)).map(this::toDomain);
    }

    @Override
    public Optional<MemoryBuildJob> findLatestByNovelId(String novelId) {
        var list = em.createQuery(
                        "SELECT j FROM MemoryBuildJobEntity j WHERE j.novelId = :novelId "
                                + "ORDER BY j.createdAt DESC", MemoryBuildJobEntity.class)
                .setParameter("novelId", novelId)
                .setMaxResults(1)
                .getResultList();
        return list.stream().findFirst().map(this::toDomain);
    }

    @Override
    public Optional<MemoryBuildJob> findActiveByNovelId(String novelId) {
        var list = em.createQuery(
                        "SELECT j FROM MemoryBuildJobEntity j WHERE j.novelId = :novelId "
                                + "AND (j.status = :running OR j.status = :pending) "
                                + "ORDER BY j.createdAt DESC", MemoryBuildJobEntity.class)
                .setParameter("novelId", novelId)
                .setParameter("running", MemoryBuildStatus.RUNNING.name())
                .setParameter("pending", MemoryBuildStatus.PENDING.name())
                .setMaxResults(1)
                .getResultList();
        return list.stream().findFirst().map(this::toDomain);
    }

    private MemoryBuildJobEntity toEntity(MemoryBuildJob job) {
        MemoryBuildJobEntity e = new MemoryBuildJobEntity();
        e.setJobId(job.jobId());
        e.setNovelId(job.novelId());
        e.setStatus(job.status());
        e.setTotalChapters(job.totalChapters());
        e.setSuccessChapters(job.successChapters());
        e.setFailedChapters(job.failedChapters());
        e.setCurrentOrdinal(job.currentOrdinal());
        e.setFailedOrdinals(job.failedOrdinals());
        e.setCreatedAt(job.createdAt());
        e.setUpdatedAt(job.updatedAt());
        return e;
    }

    private MemoryBuildJob toDomain(MemoryBuildJobEntity e) {
        return new MemoryBuildJob(
                e.getJobId(), e.getNovelId(), e.getStatus(), e.getTotalChapters(),
                e.getSuccessChapters(), e.getFailedChapters(), e.getCurrentOrdinal(),
                e.getFailedOrdinals() == null ? java.util.List.of() : e.getFailedOrdinals(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
