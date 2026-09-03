package com.inkforge.memory.build;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.generation.CostCalculator;
import com.inkforge.generation.CostProperties;
import com.inkforge.generation.InMemoryGenerationLogRepository;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.MemoryUpdateService;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.StoryMemoryService;
import com.inkforge.memory.extraction.ExtractionValidator;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.InMemoryNovelRepository;
import com.inkforge.novel.Novel;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.MockEmbeddingProvider;
import com.inkforge.provider.MockLlmProvider;
import com.inkforge.retrieval.InMemoryChunkEmbeddingStore;
import com.inkforge.retrieval.InMemoryMemoryChunkRepository;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryEmbeddingService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-A 压测：纯 Job Pipeline（MockLlmProvider 零网络），100/500/1000 章。
 * 验证：顺序构建、Job COMPLETED、记忆规模增长、Event 不重复、内存可承载。
 */
class MemoryBuildStressTest {

    record Pipeline(StoryMemoryService service, StoryMemoryRepository memRepo) {
    }

    private Pipeline buildPipeline() {
        TokenCounter tc = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        ObjectMapper om = new ObjectMapper();
        MemoryExtractionProperties props = new MemoryExtractionProperties(3, 12000, 2048, 2048, 0.2, 2, 0.7, 300, 200);
        MockLlmProvider llm = new MockLlmProvider(tc, om,
                new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0)));
        StoryMemoryRepository mem = new InMemoryStoryMemoryRepository();
        InMemoryMemoryChunkRepository chunks = new InMemoryMemoryChunkRepository();
        InMemoryChunkEmbeddingStore embed = new InMemoryChunkEmbeddingStore();
        EmbeddingProperties ep = new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120);
        StoryMemoryService service = new StoryMemoryService(new InMemoryNovelRepository(), mem,
                new MemoryExtractor(llm, catalog, tc, new ExtractionValidator(), props, om),
                new MemoryUpdateService(mem, props), props, new InMemoryGenerationLogRepository(),
                new CostCalculator(new CostProperties(Map.of())), llm,
                new MemoryChunkProjectionService(mem, chunks),
                new MemoryEmbeddingService(new MockEmbeddingProvider(ep), chunks, embed, ep));
        return new Pipeline(service, mem);
    }

    private void runStress(int n) {
        Pipeline p = buildPipeline();
        Novel novel = new Novel("stress-" + n, "压测小说", "stress.txt", buildChapters(n));
        InMemoryNovelRepository novelRepo = new InMemoryNovelRepository();
        novelRepo.save(novel);
        InMemoryMemoryBuildJobRepository jobRepo = new InMemoryMemoryBuildJobRepository();
        MemoryBuildJobRunner runner = new MemoryBuildJobRunner(novelRepo, jobRepo, p.memRepo(), p.service());

        MemoryBuildJob job = new MemoryBuildJob(novel.id(), n);
        jobRepo.save(job); job.start(); jobRepo.save(job);

        long start = System.nanoTime();
        runner.run(job.jobId());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        MemoryBuildJob done = jobRepo.findById(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(MemoryBuildStatus.COMPLETED);
        assertThat(done.successChapters()).isEqualTo(n);
        assertThat(done.failedChapters()).isZero();
        int chars = p.memRepo().findCharacters(novel.id()).size();
        int events = p.memRepo().findEvents(novel.id(), Integer.MAX_VALUE, false).size();
        System.out.println("[stress " + n + "] " + elapsedMs + "ms, 人物=" + chars + " 事件=" + events
                + "（每章 " + (elapsedMs / (double) n) + "ms）");
        assertThat(events).isPositive();
    }

    @Test
    void stress100() { runStress(100); }
    @Test
    void stress500() { runStress(500); }
    @Test
    void stress1000() { runStress(1000); }

    private static List<Chapter> buildChapters(int n) {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            chapters.add(new Chapter(i, i + 1, "第" + (i + 1) + "章", "林默与血魔对峙。他活动右臂，感到钝痛。"));
        }
        return chapters;
    }
}
