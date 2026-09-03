package com.inkforge.memory.build;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.NotFoundException;
import com.inkforge.memory.MemoryExtractionRecord;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.StoryMemoryService;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P5-A 全量 Memory Build 执行器。按 chapter ordinal 升序串行处理：
 * SUCCESS 跳过（Ground Truth = MemoryExtractionRecord）→ 否则 buildChapter → 更新 Job 进度。
 *
 * <p>协作式 pause/cancel：每章之间重新读 Job 状态，一旦非 RUNNING 即停（当前章自然结束后停）。
 * <p>run/retryFailed 提供同步版本供测试；@Async 包装走 memoryBuildExecutor。
 */
@Component
public class MemoryBuildJobRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryBuildJobRunner.class);

    private final NovelRepository novelRepository;
    private final MemoryBuildJobRepository jobRepository;
    private final StoryMemoryRepository memoryRepository;
    private final StoryMemoryService storyMemoryService;

    public MemoryBuildJobRunner(NovelRepository novelRepository,
                                MemoryBuildJobRepository jobRepository,
                                StoryMemoryRepository memoryRepository,
                                StoryMemoryService storyMemoryService) {
        this.novelRepository = novelRepository;
        this.jobRepository = jobRepository;
        this.memoryRepository = memoryRepository;
        this.storyMemoryService = storyMemoryService;
    }

    @Async("memoryBuildExecutor")
    public void runAsync(String jobId) {
        run(jobId);
    }

    @Async("memoryBuildExecutor")
    public void retryFailedAsync(String jobId) {
        retryFailed(jobId);
    }

    /** 全量顺序执行（同步，可测试）。 */
    public void run(String jobId) {
        MemoryBuildJob job = reload(jobId);
        if (job.status() != MemoryBuildStatus.RUNNING) return;
        Novel novel = requireNovel(job.novelId());
        List<Chapter> chapters = novel.chapters();

        for (Chapter chapter : chapters) {
            job = reload(jobId);
            if (job.status() != MemoryBuildStatus.RUNNING) break; // 协作式 pause/cancel
            if (isSucceeded(job.novelId(), chapter.ordinal())) {
                job.recordChapter(true, chapter.ordinal()); // 跳过也计成功，保证 resume 后进度正确
                jobRepository.save(job);
                continue; // SUCCESS 跳过：不重复 apply（Event append 语义）
            }
            MemoryExtractionRecord rec = storyMemoryService.buildChapter(job.novelId(), chapter);
            job.recordChapter("SUCCESS".equals(rec.status()), chapter.ordinal());
            jobRepository.save(job);
        }
        finalize(jobId);
    }

    /** 只重试失败章节（SUCCESS 不重扫 LLM）。 */
    public void retryFailed(String jobId) {
        MemoryBuildJob job = reload(jobId);
        if (job.status() != MemoryBuildStatus.RUNNING) return;
        Novel novel = requireNovel(job.novelId());

        for (int ordinal : job.failedOrdinals()) {
            job = reload(jobId);
            if (job.status() != MemoryBuildStatus.RUNNING) break;
            if (isSucceeded(job.novelId(), ordinal)) continue; // 已被其他路径恢复
            Chapter chapter = novel.chapters().get(ordinal);
            MemoryExtractionRecord rec = storyMemoryService.buildChapter(job.novelId(), chapter);
            job.recordChapter("SUCCESS".equals(rec.status()), ordinal);
            jobRepository.save(job);
        }
        finalize(jobId);
    }

    /** 结束：RUNNING → COMPLETED（无失败）/ PARTIAL_FAILED（有失败）。 */
    private void finalize(String jobId) {
        MemoryBuildJob job = reload(jobId);
        if (job.status() != MemoryBuildStatus.RUNNING) return; // 已被 pause/cancel
        if (job.failedChapters() == 0) {
            job.finishSuccess();
        } else {
            job.finishPartialFailed();
        }
        jobRepository.save(job);
        log.info("Memory Build Job {} 完成: status={} success={} failed={}",
                jobId, job.status(), job.successChapters(), job.failedChapters());
    }

    private boolean isSucceeded(String novelId, int ordinal) {
        return memoryRepository.findExtractionRecord(novelId, ordinal)
                .filter(MemoryExtractionRecord::succeeded)
                .isPresent();
    }

    private Novel requireNovel(String novelId) {
        return novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + novelId));
    }

    private MemoryBuildJob reload(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("Memory Build Job 不存在: " + jobId));
    }
}
