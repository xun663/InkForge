package com.inkforge.memory;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.generation.CostCalculator;
import com.inkforge.generation.CostProperties;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.generation.InMemoryGenerationLogRepository;
import com.inkforge.memory.extraction.ExtractionValidator;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.InMemoryNovelRepository;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.MockLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full extraction pipeline with the Mock provider — zero API key — proving
 * summary + characters + facts + events land in Story Memory with stats.
 */
class StoryMemoryServiceTest {

    private static final String CHAPTER_TEXT = "林默与血魔在后山对峙。他试着活动右臂，手腕处顿时传来一阵钝痛。";

    private StoryMemoryService service;
    private StoryMemoryRepository memoryRepository;
    private GenerationLogRepository generationLogRepository;
    private NovelRepository novelRepository;
    private com.inkforge.retrieval.InMemoryMemoryChunkRepository chunkRepository;
    private com.inkforge.retrieval.InMemoryChunkEmbeddingStore chunkEmbeddingStore;
    private com.inkforge.retrieval.MemoryEmbeddingService embeddingService;
    private MemoryExtractor extractor;
    private MemoryUpdateService updateService;
    private MemoryExtractionProperties properties;
    private MockLlmProvider llmProvider;

    @BeforeEach
    void setUp() {
        TokenCounter tokenCounter = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        ObjectMapper objectMapper = new ObjectMapper();
        properties = new MemoryExtractionProperties(3, 12000, 2048, 2048, 0.2, 2, 0.7, 300, 200);
        llmProvider = new MockLlmProvider(tokenCounter, objectMapper,
                new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0)));

        novelRepository = new InMemoryNovelRepository();
        memoryRepository = new InMemoryStoryMemoryRepository();
        generationLogRepository = new InMemoryGenerationLogRepository();
        chunkRepository = new com.inkforge.retrieval.InMemoryMemoryChunkRepository();
        chunkEmbeddingStore = new com.inkforge.retrieval.InMemoryChunkEmbeddingStore();
        extractor = new MemoryExtractor(llmProvider, catalog, tokenCounter,
                new ExtractionValidator(), properties, objectMapper);
        updateService = new MemoryUpdateService(memoryRepository, properties);
        embeddingService = new com.inkforge.retrieval.MemoryEmbeddingService(
                new com.inkforge.provider.MockEmbeddingProvider(
                        new com.inkforge.provider.EmbeddingProperties(
                                "mock", "bge-m3", "https://unused", "", 1024, 16, 120)),
                chunkRepository, chunkEmbeddingStore,
                new com.inkforge.provider.EmbeddingProperties(
                        "mock", "bge-m3", "https://unused", "", 1024, 16, 120));
        service = new StoryMemoryService(novelRepository, memoryRepository, extractor,
                updateService, properties, generationLogRepository,
                new CostCalculator(new CostProperties(Map.of())), llmProvider,
                new com.inkforge.retrieval.MemoryChunkProjectionService(
                        memoryRepository, chunkRepository),
                embeddingService);
    }

    private void seedNovel(String novelId, int chapterCount) {
        List<Chapter> chapters = java.util.stream.IntStream.range(0, chapterCount)
                .mapToObj(i -> new Chapter(i, i + 1, "第" + (i + 1) + "章 试炼", CHAPTER_TEXT))
                .toList();
        novelRepository.save(new Novel(novelId, "测试小说", "t.txt", chapters));
    }

    @Test
    void extractRecentBuildsMemoryForLastWindowChapters() {
        seedNovel("n1", 5);

        List<MemoryExtractionRecord> records = service.extractRecent("n1", 3);

        assertThat(records).hasSize(3);
        assertThat(records).allMatch(MemoryExtractionRecord::succeeded);
        assertThat(records).extracting(MemoryExtractionRecord::chapterOrdinal)
                .containsExactly(2, 3, 4);
        assertThat(records.getFirst().stats().quotesValidated()).isPositive();
        assertThat(records.getFirst().stats().quotesRejected()).isZero();
        assertThat(records.getFirst().stats().tokenUsage().promptTokens()).isPositive();

        // summary for the breakpoint chapter, characters with facts, events, extraction cost log
        assertThat(memoryRepository.findSummary("n1", 4)).isPresent();
        assertThat(memoryRepository.findCharacterByName("n1", "林默")).isPresent();
        assertThat(memoryRepository.findCharacterByName("n1", "血魔")).isPresent();
        assertThat(memoryRepository.findEvents("n1", 10, true)).hasSize(3);
        assertThat(generationLogRepository.findByNovelId("n1").stream()
                .filter(log -> "EXTRACTION".equals(log.type()))).hasSize(3);
    }

    @Test
    void extractIsIdempotentForAlreadyProcessedChapters() {
        seedNovel("n1", 4);
        service.extractRecent("n1", 3);
        List<MemoryExtractionRecord> second = service.extractRecent("n1", 3);

        assertThat(second).isEmpty();
        assertThat(memoryRepository.findExtractionRecords("n1")).hasSize(3);
    }

    @Test
    void overviewAggregatesMemoryAndStats() {
        seedNovel("n1", 4);
        service.extractRecent("n1", 3);

        StoryMemoryService.MemoryOverview overview = service.overview("n1");

        assertThat(overview.lastExtractedOrdinal()).isEqualTo(3);
        assertThat(overview.characters()).extracting(StoryMemoryService.CharacterView::name)
                .containsExactlyInAnyOrder("林默", "血魔");
        assertThat(overview.characters().stream()
                .filter(c -> c.name().equals("林默")).findFirst().orElseThrow()
                .currentFacts()).isNotEmpty();
        assertThat(overview.recentEvents()).hasSize(3);
        assertThat(overview.recentSummaries()).hasSize(3);
        assertThat(overview.aggregateStats().chaptersExtracted()).isEqualTo(3);
        assertThat(overview.aggregateStats().characters()).isEqualTo(2);
        assertThat(overview.aggregateStats().events()).isEqualTo(3);
    }

    @Test
    void projectionHookProjectsChunksAfterExtraction() {
        seedNovel("n1", 4);
        service.extractRecent("n1", 3);

        // P3-B hook: each extracted chapter got its chunks projected.
        // Mock 提取的事实全是 CURRENT → 按投影规则被排除，每章 = SUMMARY + EVENT = 2 个 chunk。
        assertThat(chunkRepository.findByNovelId("n1")).hasSize(6);
        assertThat(chunkRepository.findByNovelIdAndChapter("n1", 3)).hasSize(2);
        assertThat(chunkRepository.findByNovelIdAndChapter("n1", 3))
                .extracting(c -> c.memoryType().name())
                .containsExactlyInAnyOrder("SUMMARY", "EVENT");
    }

    @Test
    void projectionFailureNeverBreaksExtraction() {
        seedNovel("n1", 4);
        com.inkforge.retrieval.MemoryChunkRepository throwing = new com.inkforge.retrieval.MemoryChunkRepository() {
            @Override
            public java.util.List<com.inkforge.retrieval.MemoryChunk> findByNovelId(String novelId) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<com.inkforge.retrieval.MemoryChunk> findByNovelIdAndChapter(String novelId, int chapterOrdinal) {
                return java.util.List.of();
            }

            @Override
            public void replaceForChapter(String novelId, int chapterOrdinal,
                                          java.util.List<com.inkforge.retrieval.MemoryChunk> chunks) {
                throw new IllegalStateException("模拟投影失败");
            }

            @Override
            public void deleteByNovelId(String novelId) {
            }

            @Override
            public long revision(String novelId) {
                return 0;
            }
        };
        StoryMemoryService failingService = new StoryMemoryService(novelRepository, memoryRepository,
                extractor, updateService, properties, generationLogRepository,
                new CostCalculator(new CostProperties(Map.of())), llmProvider,
                new com.inkforge.retrieval.MemoryChunkProjectionService(memoryRepository, throwing),
                embeddingService);

        List<MemoryExtractionRecord> records = failingService.extractRecent("n1", 3);

        // Story Memory 完全不受投影失败影响
        assertThat(records).hasSize(3);
        assertThat(records).allMatch(MemoryExtractionRecord::succeeded);
        assertThat(memoryRepository.findSummary("n1", 3)).isPresent();
        assertThat(memoryRepository.findCharacterByName("n1", "林默")).isPresent();
    }

    @Test
    void embeddingRunsAfterExtractionAndActuallyProducesVectors() {
        seedNovel("n1", 4);
        service.extractRecent("n1", 3);

        // Vector 接线：每个有 chunk 的章节都已生成 embedding（Mock 零 Key）
        assertThat(chunkEmbeddingStore.find("SUMMARY:n1:3")).isPresent();
        assertThat(chunkEmbeddingStore.find("SUMMARY:n1:3").get().values()).hasSize(1024);
        assertThat(chunkEmbeddingStore.find("EVENT:"
                + memoryRepository.findEvents("n1", 10, false).getFirst().id())).isPresent();
    }

    @Test
    void embeddingFailureNeverBreaksExtraction() {
        seedNovel("n1", 4);
        com.inkforge.retrieval.MemoryEmbeddingService throwing =
                org.mockito.Mockito.mock(com.inkforge.retrieval.MemoryEmbeddingService.class);
        org.mockito.Mockito.when(throwing.embedNovel(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("模拟 embedding 失败"));
        StoryMemoryService failingService = new StoryMemoryService(novelRepository, memoryRepository,
                extractor, updateService, properties, generationLogRepository,
                new CostCalculator(new CostProperties(Map.of())), llmProvider,
                new com.inkforge.retrieval.MemoryChunkProjectionService(memoryRepository, chunkRepository),
                throwing);

        List<MemoryExtractionRecord> records = failingService.extractRecent("n1", 3);

        // embedding 失败 → 提取/投影/记忆全部不受影响
        assertThat(records).hasSize(3);
        assertThat(records).allMatch(MemoryExtractionRecord::succeeded);
        assertThat(memoryRepository.findSummary("n1", 3)).isPresent();
        assertThat(chunkRepository.findByNovelIdAndChapter("n1", 3)).isNotEmpty(); // projection 照常
    }

    @Test
    void unknownNovelIsRejected() {
        assertThatThrownBy(() -> service.extractRecent("no-such", 3))
                .isInstanceOf(com.inkforge.common.NotFoundException.class);
        assertThatThrownBy(() -> service.overview("no-such"))
                .isInstanceOf(com.inkforge.common.NotFoundException.class);
    }
}
