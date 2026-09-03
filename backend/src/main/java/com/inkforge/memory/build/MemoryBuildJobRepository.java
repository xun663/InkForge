package com.inkforge.memory.build;

import java.util.Optional;

/**
 * Persistence port for MemoryBuildJob.
 *
 * <p>职责：Job 生命周期状态。章节级事实仍走 MemoryExtractionRecord。
 * InMemory 模式重启即失；PostgreSQL 模式持久化可恢复。
 */
public interface MemoryBuildJobRepository {

    MemoryBuildJob save(MemoryBuildJob job);

    Optional<MemoryBuildJob> findById(String jobId);

    /** 某小说最近一次 Job（用于前端恢复显示）。 */
    Optional<MemoryBuildJob> findLatestByNovelId(String novelId);

    /** 某小说当前活跃（PENDING/RUNNING）的 Job——并发保护用。 */
    Optional<MemoryBuildJob> findActiveByNovelId(String novelId);
}
