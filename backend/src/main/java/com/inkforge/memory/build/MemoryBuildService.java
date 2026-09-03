package com.inkforge.memory.build;

import com.inkforge.common.NotFoundException;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * P5-A Full Memory Build 编排：start / pause / resume / cancel / retry-failed / current。
 *
 * <p>并发保护：同一 novel 同一时刻只允许一个 PENDING/RUNNING Job
 * （start/resume 前置检查 + postgres 部分唯一索引兜底）。
 * <p>Job 状态与进度持久化到 repository；每章事实仍由 MemoryExtractionRecord 记录。
 */
@Service
public class MemoryBuildService {

    private final NovelRepository novelRepository;
    private final MemoryBuildJobRepository jobRepository;
    private final MemoryBuildJobRunner runner;

    public MemoryBuildService(NovelRepository novelRepository,
                              MemoryBuildJobRepository jobRepository,
                              MemoryBuildJobRunner runner) {
        this.novelRepository = novelRepository;
        this.jobRepository = jobRepository;
        this.runner = runner;
    }

    /** 开始（或重新）全量构建。返回 Job（RUNNING）。 */
    public MemoryBuildJob start(String novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + novelId));
        ensureNoActive(novelId);
        MemoryBuildJob job = new MemoryBuildJob(novelId, novel.chapterCount());
        jobRepository.save(job);
        job.start();
        jobRepository.save(job);
        runner.runAsync(job.jobId());
        return job;
    }

    public MemoryBuildJob pause(String jobId) {
        MemoryBuildJob job = require(jobId);
        job.pause();
        jobRepository.save(job);
        return job;
    }

    public MemoryBuildJob resume(String jobId) {
        MemoryBuildJob job = require(jobId);
        ensureNoActiveOther(job.novelId(), jobId);
        job.resume();
        jobRepository.save(job);
        runner.runAsync(job.jobId());
        return job;
    }

    public MemoryBuildJob cancel(String jobId) {
        MemoryBuildJob job = require(jobId);
        job.cancel();
        jobRepository.save(job);
        return job;
    }

    /** 重试失败章节：PARTIAL_FAILED（或 COMPLETED 但有失败）→ RUNNING 并只跑失败章。 */
    public MemoryBuildJob retryFailed(String jobId) {
        MemoryBuildJob job = require(jobId);
        if (job.failedChapters() == 0) {
            throw new IllegalStateException("该 Job 没有失败章节可重试");
        }
        ensureNoActiveOther(job.novelId(), jobId);
        job.retry(); // PARTIAL_FAILED → RUNNING
        jobRepository.save(job);
        runner.retryFailedAsync(job.jobId());
        return job;
    }

    /** 当前 Job（最近一次，用于前端恢复显示）。 */
    public Optional<MemoryBuildJob> current(String novelId) {
        return jobRepository.findLatestByNovelId(novelId);
    }

    public MemoryBuildJob get(String jobId) {
        return require(jobId);
    }

    private MemoryBuildJob require(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("Memory Build Job 不存在: " + jobId));
    }

    private void ensureNoActive(String novelId) {
        jobRepository.findActiveByNovelId(novelId).ifPresent(j -> {
            throw new IllegalStateException("该小说已有运行中的记忆构建任务: " + j.jobId());
        });
    }

    private void ensureNoActiveOther(String novelId, String excludeJobId) {
        jobRepository.findActiveByNovelId(novelId)
                .filter(j -> !j.jobId().equals(excludeJobId))
                .ifPresent(j -> {
                    throw new IllegalStateException("该小说已有运行中的记忆构建任务: " + j.jobId());
                });
    }
}
