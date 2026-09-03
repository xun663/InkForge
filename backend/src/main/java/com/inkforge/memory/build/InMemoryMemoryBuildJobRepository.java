package com.inkforge.memory.build;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory Job repository。重启即失（开发/Demo 模式，与 InMemory Memory 一致）。
 * 同一 novel 并发保护用同步 map + 活跃状态检查。
 */
@Repository
public class InMemoryMemoryBuildJobRepository implements MemoryBuildJobRepository {

    private final Map<String, MemoryBuildJob> byId = new ConcurrentHashMap<>();

    @Override
    public MemoryBuildJob save(MemoryBuildJob job) {
        byId.put(job.jobId(), job);
        return job;
    }

    @Override
    public Optional<MemoryBuildJob> findById(String jobId) {
        return Optional.ofNullable(byId.get(jobId));
    }

    @Override
    public Optional<MemoryBuildJob> findLatestByNovelId(String novelId) {
        return byId.values().stream()
                .filter(j -> j.novelId().equals(novelId))
                .max(Comparator.comparing(MemoryBuildJob::createdAt));
    }

    @Override
    public Optional<MemoryBuildJob> findActiveByNovelId(String novelId) {
        return byId.values().stream()
                .filter(j -> j.novelId().equals(novelId))
                .filter(j -> j.status() == MemoryBuildStatus.PENDING || j.status() == MemoryBuildStatus.RUNNING)
                .findFirst();
    }
}
