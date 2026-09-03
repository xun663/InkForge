package com.inkforge.memory.build;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.generation.CostCalculator;
import com.inkforge.generation.CostProperties;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.generation.InMemoryGenerationLogRepository;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.MemoryExtractionRecord;
import com.inkforge.memory.MemoryExtractionStats;
import com.inkforge.memory.MemoryUpdateService;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.StoryMemoryService;
import com.inkforge.memory.extraction.ExtractionValidator;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.InMemoryNovelRepository;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.MockEmbeddingProvider;
import com.inkforge.provider.MockLlmProvider;
import com.inkforge.retrieval.InMemoryChunkEmbeddingStore;
import com.inkforge.retrieval.InMemoryMemoryChunkRepository;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P5-A：MemoryBuildJob 状态机、Runner 顺序/跳过/失败/重试/暂停/取消、并发保护、幂等重复安全。 */
class MemoryBuildJobTest {

    private Novel novel;
    private NovelRepository novelRepository;
    private InMemoryMemoryBuildJobRepository jobRepository;
    private StoryMemoryRepository memoryRepository;
    private StoryMemoryService storyService;

    @BeforeEach
    void setUp() {
        novel = new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "第一章", "内容A"),
                new Chapter(1, 2, "第二章", "内容B"),
                new Chapter(2, 3, "第三章", "内容C"),
                new Chapter(3, 4, "第四章", "内容D")));
        novelRepository = new InMemoryNovelRepository();
        novelRepository.save(novel);
        jobRepository = new InMemoryMemoryBuildJobRepository();
        memoryRepository = mock(StoryMemoryRepository.class);
        storyService = mock(StoryMemoryService.class);
        // 默认：无任何 SUCCESS 记录
        when(memoryRepository.findExtractionRecord(anyString(), anyInt()))
                .thenReturn(java.util.Optional.empty());
    }

    private MemoryBuildJobRunner runner() {
        return new MemoryBuildJobRunner(novelRepository, jobRepository, memoryRepository, storyService);
    }

    private MemoryExtractionRecord rec(String novelId, int ordinal, boolean success) {
        return new MemoryExtractionRecord(novelId, ordinal, success ? "SUCCESS" : "FAILED",
                success ? null : "模拟失败", "mock",
                new MemoryExtractionStats(1, 1, 1, 1, 0, 0, 1, null), Instant.now());
    }

    @Test
    void lifecycleTransitions() {
        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        assertThat(job.status()).isEqualTo(MemoryBuildStatus.PENDING);
        job.start();
        assertThat(job.status()).isEqualTo(MemoryBuildStatus.RUNNING);
        job.pause();
        assertThat(job.status()).isEqualTo(MemoryBuildStatus.PAUSED);
        job.resume();
        assertThat(job.status()).isEqualTo(MemoryBuildStatus.RUNNING);
        job.finishSuccess();
        assertThat(job.status()).isEqualTo(MemoryBuildStatus.COMPLETED);
    }

    @Test
    void illegalTransitionsThrow() {
        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        assertThatThrownBy(job::pause).isInstanceOf(IllegalStateException.class); // PENDING→PAUSED 非法
        assertThatThrownBy(job::resume).isInstanceOf(IllegalStateException.class); // PENDING→RUNNING 无来源
        job.start();
        assertThatThrownBy(() -> job.resume()).isInstanceOf(IllegalStateException.class); // RUNNING→RUNNING 非法
        job.finishSuccess();
        assertThatThrownBy(() -> job.finishPartialFailed()).isInstanceOf(IllegalStateException.class); // COMPLETED 后非法
        assertThatThrownBy(job::cancel).isInstanceOf(IllegalStateException.class); // COMPLETED→CANCELLED 非法
    }

    @Test
    void sequentialOrderStrictlyAscending() {
        when(storyService.buildChapter(eq("n1"), any(Chapter.class)))
                .thenAnswer(inv -> {
                    Chapter c = inv.getArgument(1);
                    return rec("n1", c.ordinal(), true);
                });
        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        jobRepository.save(job);
        job.start();
        jobRepository.save(job);

        runner().run(job.jobId());

        // 必须严格按 ordinal 顺序（Mockito InOrder 也可，这里用记录顺序验证）
        var inOrder = org.mockito.Mockito.inOrder(storyService);
        inOrder.verify(storyService).buildChapter(eq("n1"), argOrdinal(0));
        inOrder.verify(storyService).buildChapter(eq("n1"), argOrdinal(1));
        inOrder.verify(storyService).buildChapter(eq("n1"), argOrdinal(2));
        inOrder.verify(storyService).buildChapter(eq("n1"), argOrdinal(3));
        MemoryBuildJob done = jobRepository.findById(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(MemoryBuildStatus.COMPLETED);
        assertThat(done.successChapters()).isEqualTo(4);
    }

    @Test
    void skipsSucceededChapters() {
        // ordinal 1 已有 SUCCESS → 跳过，不重复 buildChapter
        when(memoryRepository.findExtractionRecord(eq("n1"), eq(1)))
                .thenReturn(java.util.Optional.of(rec("n1", 1, true)));
        when(storyService.buildChapter(eq("n1"), any(Chapter.class)))
                .thenAnswer(inv -> rec("n1", ((Chapter) inv.getArgument(1)).ordinal(), true));

        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        jobRepository.save(job); job.start(); jobRepository.save(job);
        runner().run(job.jobId());

        verify(storyService, never()).buildChapter(eq("n1"), argOrdinal(1));
        verify(storyService).buildChapter(eq("n1"), argOrdinal(0));
        verify(storyService).buildChapter(eq("n1"), argOrdinal(2));
        MemoryBuildJob done = jobRepository.findById(job.jobId()).orElseThrow();
        assertThat(done.successChapters()).isEqualTo(4); // 0,2,3 构建 + 1 跳过（跳过也计成功）
    }

    @Test
    void singleFailureDoesNotBlockAndYieldsPartialFailed() {
        when(storyService.buildChapter(eq("n1"), any(Chapter.class)))
                .thenAnswer(inv -> rec("n1", ((Chapter) inv.getArgument(1)).ordinal(), ((Chapter) inv.getArgument(1)).ordinal() != 2));
        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        jobRepository.save(job); job.start(); jobRepository.save(job);
        runner().run(job.jobId());

        MemoryBuildJob done = jobRepository.findById(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(MemoryBuildStatus.PARTIAL_FAILED);
        assertThat(done.failedOrdinals()).containsExactly(2);
        assertThat(done.successChapters()).isEqualTo(3);
        assertThat(done.failedChapters()).isEqualTo(1);
    }

    @Test
    void retryFailedOnlyProcessesFailedOrdinals() {
        // 先造一个 failed=[1,3] 的 job（RUNNING 状态供 retry）
        when(storyService.buildChapter(eq("n1"), any(Chapter.class)))
                .thenAnswer(inv -> {
                    int o = ((Chapter) inv.getArgument(1)).ordinal();
                    return rec("n1", o, o != 1 && o != 3);
                });
        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        jobRepository.save(job); job.start(); jobRepository.save(job);
        runner().run(job.jobId());
        assertThat(jobRepository.findById(job.jobId()).orElseThrow().failedOrdinals()).containsExactly(1, 3);

        // retry：只有 1,3 会被重新 buildChapter
        MemoryBuildJob failed = jobRepository.findById(job.jobId()).orElseThrow();
        failed.retry(); // PARTIAL_FAILED → RUNNING
        jobRepository.save(failed);
        runner().retryFailed(job.jobId());

        verify(storyService, org.mockito.Mockito.times(2)).buildChapter(eq("n1"), argOrdinal(1));
        verify(storyService, org.mockito.Mockito.times(2)).buildChapter(eq("n1"), argOrdinal(3));
        // 0,2 只被 initial run 调用一次
        verify(storyService, org.mockito.Mockito.times(1)).buildChapter(eq("n1"), argOrdinal(0));
        verify(storyService, org.mockito.Mockito.times(1)).buildChapter(eq("n1"), argOrdinal(2));
    }

    @Test
    void pauseStopsAfterCurrentChapter() {
        // buildChapter 在处理 ordinal 1 时把 job 置为 PAUSED → runner 处理完 1 后应停下，不处理 2,3
        when(storyService.buildChapter(eq("n1"), any(Chapter.class)))
                .thenAnswer(inv -> {
                    int o = ((Chapter) inv.getArgument(1)).ordinal();
                    if (o == 1) {
                        MemoryBuildJob j = jobRepository.findById("j1").orElseThrow();
                        j.pause();
                        jobRepository.save(j);
                    }
                    return rec("n1", o, true);
                });
        MemoryBuildJob job = new MemoryBuildJob("j1", "n1", MemoryBuildStatus.RUNNING, 4,
                0, 0, -1, new java.util.ArrayList<>(), Instant.now(), Instant.now());
        jobRepository.save(job);
        runner().run(job.jobId());

        verify(storyService, never()).buildChapter(eq("n1"), argOrdinal(2));
        verify(storyService, never()).buildChapter(eq("n1"), argOrdinal(3));
        MemoryBuildJob paused = jobRepository.findById("j1").orElseThrow();
        assertThat(paused.status()).isEqualTo(MemoryBuildStatus.PAUSED);
    }

    @Test
    void cancelStopsAndCannotResume() {
        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        jobRepository.save(job); job.start(); job.cancel(); jobRepository.save(job);
        // cancel 后 run 不做任何事（状态非 RUNNING）
        runner().run(job.jobId());
        verify(storyService, never()).buildChapter(anyString(), any(Chapter.class));
        assertThatThrownBy(job::resume).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentStartRejected() {
        MemoryBuildService service = new MemoryBuildService(novelRepository, jobRepository, runner());
        // 先造一个 RUNNING job
        MemoryBuildJob active = new MemoryBuildJob("n1", 4);
        jobRepository.save(active); active.start(); jobRepository.save(active);
        assertThatThrownBy(() -> service.start("n1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有运行中");
    }

    // ---- 真实装配：幂等（重复 Build 不重复 Event） ----
    @Test
    void duplicateBuildDoesNotDuplicateEvents() {
        StoryMemoryRepository realMem = new InMemoryStoryMemoryRepository();
        InMemoryMemoryChunkRepository chunks = new InMemoryMemoryChunkRepository();
        InMemoryChunkEmbeddingStore embedStore = new InMemoryChunkEmbeddingStore();
        TokenCounter tc = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        ObjectMapper om = new ObjectMapper();
        MemoryExtractionProperties props = new MemoryExtractionProperties(3, 12000, 2048, 2048, 0.2, 2, 0.7, 300, 200);
        MockLlmProvider llm = new MockLlmProvider(tc, om,
                new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0)));
        GenerationLogRepository genLog = new InMemoryGenerationLogRepository();
        EmbeddingProperties ep = new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120);
        StoryMemoryService realService = new StoryMemoryService(novelRepository, realMem,
                new MemoryExtractor(llm, catalog, tc, new ExtractionValidator(), props, om),
                new MemoryUpdateService(realMem, props), props, genLog,
                new CostCalculator(new CostProperties(Map.of())), llm,
                new MemoryChunkProjectionService(realMem, chunks),
                new MemoryEmbeddingService(new MockEmbeddingProvider(ep), chunks, embedStore, ep));
        MemoryBuildJobRunner realRunner = new MemoryBuildJobRunner(novelRepository, jobRepository, realMem, realService);

        MemoryBuildJob job = new MemoryBuildJob("n1", 4);
        jobRepository.save(job); job.start(); jobRepository.save(job);
        realRunner.run(job.jobId());
        int eventsAfterFirst = realMem.findEvents("n1", Integer.MAX_VALUE, false).size();
        assertThat(eventsAfterFirst).isPositive();

        // 第二次 run：SUCCESS 全部跳过 → 不重复 apply → Event 不翻倍
        MemoryBuildJob job2 = new MemoryBuildJob("n1", 4);
        jobRepository.save(job2); job2.start(); jobRepository.save(job2);
        realRunner.run(job2.jobId());
        int eventsAfterSecond = realMem.findEvents("n1", Integer.MAX_VALUE, false).size();
        assertThat(eventsAfterSecond).isEqualTo(eventsAfterFirst);
        // job2 全跳过但仍计成功（进度正确）
        MemoryBuildJob job2Done = jobRepository.findById(job2.jobId()).orElseThrow();
        assertThat(job2Done.successChapters()).isEqualTo(4);
        assertThat(job2Done.failedChapters()).isZero();
    }

    private static com.inkforge.chapter.Chapter argOrdinal(int ordinal) {
        return org.mockito.ArgumentMatchers.argThat(c -> c.ordinal() == ordinal);
    }
}
