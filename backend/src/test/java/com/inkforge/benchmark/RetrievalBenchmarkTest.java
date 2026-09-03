package com.inkforge.benchmark;

import com.inkforge.benchmark.BenchmarkQueries.BenchmarkQuery;
import com.inkforge.benchmark.Metrics.Result;
import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.retrieval.RetrievalQueryBuilder;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.novel.InMemoryNovelRepository;
import com.inkforge.novel.Novel;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.MockEmbeddingProvider;
import com.inkforge.retrieval.HybridRetrievalService;
import com.inkforge.retrieval.InMemoryChunkEmbeddingStore;
import com.inkforge.retrieval.InMemoryMemoryChunkRepository;
import com.inkforge.retrieval.InMemoryVectorRetriever;
import com.inkforge.retrieval.LuceneBm25Retriever;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryChunkRepository;
import com.inkforge.retrieval.MemoryEmbeddingService;
import com.inkforge.retrieval.MemoryRetriever;
import com.inkforge.retrieval.PassThroughReranker;
import com.inkforge.retrieval.RetrievalResult;
import com.inkforge.retrieval.RetrievalQuery;
import com.inkforge.retrieval.RrfFusion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-G 消融实验（固定条件：固定小说 / 固定 24 条标注 / 固定配置 / Mock provider 零 Key）。
 *
 * <p>8 组方法：
 * 1. baseline        —— 最近 3 章原文窗口（无检索；Recall 反映断点附近覆盖度）
 * 2. p2-memory       —— 最近 3 章摘要 + 事件候选（无检索）
 * 3. bm25            —— 单 BM25
 * 4. vector          —— 单 Vector
 * 5. hybrid-concat   —— BM25+Vector 合并去重（不融合排序，先 BM25 后 Vector）
 * 6. hybrid-rrf      —— BM25+Vector → RRF
 * 7. rrf-rerank      —— RRF → PassThrough(top-8)（默认 reranker，非 LLM）
 * 8. multi-query     —— 标注 query + character + thread 三路完整管线，取最高分合并
 *
 * <p>检索全部走生产代码；结果写入 target/benchmark-results.md（人工复制到 docs/ 存档）。
 * 不修改任何生产检索实现。
 */
class RetrievalBenchmarkTest {

    private static final int TOP_K = 10;
    private static final String MULTI_CHARACTER_QUERY = "方源 白凝冰 血手魔尊";

    private static StoryMemoryRepository memoryRepository;
    private static MemoryChunkRepository chunkRepository;
    private static InMemoryChunkEmbeddingStore embeddingStore;
    private static Novel novel;
    private static LuceneBm25Retriever bm25Retriever;
    private static InMemoryVectorRetriever vectorRetriever;
    private static HybridRetrievalService hybrid;
    private static RetrievalQueryBuilder queryBuilder;

    @BeforeAll
    static void setUp() {
        memoryRepository = new InMemoryStoryMemoryRepository();
        chunkRepository = new InMemoryMemoryChunkRepository();
        embeddingStore = new InMemoryChunkEmbeddingStore();
        novel = BenchmarkNovelData.loadNovel();
        new InMemoryNovelRepository().save(novel);
        BenchmarkNovelData.seedMemory(memoryRepository);
        BenchmarkNovelData.seedFacts(memoryRepository);

        MemoryChunkProjectionService projection =
                new MemoryChunkProjectionService(memoryRepository, chunkRepository);
        for (Chapter chapter : novel.chapters()) {
            projection.projectChapter(novel.id(), chapter.ordinal());
        }

        EmbeddingProperties embeddingProperties =
                new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120);
        MockEmbeddingProvider embeddingProvider = new MockEmbeddingProvider(embeddingProperties);
        new MemoryEmbeddingService(embeddingProvider, chunkRepository, embeddingStore, embeddingProperties)
                .embedNovel(novel.id());

        bm25Retriever = new LuceneBm25Retriever(chunkRepository);
        vectorRetriever = new InMemoryVectorRetriever(embeddingProvider, chunkRepository,
                embeddingStore, embeddingProperties);
        hybrid = new HybridRetrievalService(bm25Retriever, vectorRetriever,
                new PassThroughReranker(),
                new com.inkforge.retrieval.RetrievalProperties(30, 30, 30, 8, 60, "passthrough", 15, 200));
        queryBuilder = new RetrievalQueryBuilder(memoryRepository);
    }

    @Test
    void runsEightAblationGroupsAndWritesResults() throws Exception {
        Map<String, List<Result>> chunkResults = new LinkedHashMap<>();
        Map<String, List<Result>> chapterResults = new LinkedHashMap<>();
        Map<String, List<RetrievalResult>> perQuery = new LinkedHashMap<>();

        for (String method : methods()) {
            List<Result> chunk = new ArrayList<>();
            List<Result> chapter = new ArrayList<>();
            for (BenchmarkQuery bq : BenchmarkQueries.QUERIES) {
                List<RetrievalResult> results = retrieve(method, bq);
                perQuery.put(method + "|" + bq.query(), results);
                chunk.add(Metrics.compute(results, bq.golds(), bq.helpfulness(), true));
                chapter.add(Metrics.compute(results, bq.golds(), bq.helpfulness(), false));
            }
            chunkResults.put(method, chunk);
            chapterResults.put(method, chapter);
        }

        String table = renderTable(chunkResults, chapterResults);
        System.out.println("\n================ P3-G 消融实验汇总 ================\n" + table);

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/benchmark-results.md"), table);

        // sanity：数据有效性（不做指标数值断言——不制造漂亮数字）
        assertThat(chunkResults.get("bm25")).isNotEmpty();
        assertThat(chapterResults.get("multi-query")).isNotEmpty();
        boolean anyHit = chapterResults.values().stream()
                .flatMap(List::stream)
                .anyMatch(r -> r.recall10() > 0);
        assertThat(anyHit).as("测试集与检索组件应至少命中若干 gold（数据有效性）").isTrue();
    }

    private static List<String> methods() {
        return List.of("baseline", "p2-memory", "bm25", "vector", "hybrid-concat",
                "hybrid-rrf", "rrf-rerank", "multi-query");
    }

    private static List<RetrievalResult> retrieve(String method, BenchmarkQuery bq) {
        return switch (method) {
            case "baseline" -> baselineCandidates();
            case "p2-memory" -> p2MemoryCandidates();
            case "bm25" -> bm25Retriever.retrieve(novel.id(), bq.query(), TOP_K);
            case "vector" -> vectorRetriever.retrieve(novel.id(), bq.query(), TOP_K);
            case "hybrid-concat" -> concat(bm25Retriever.retrieve(novel.id(), bq.query(), 30),
                    vectorRetriever.retrieve(novel.id(), bq.query(), 30));
            case "hybrid-rrf" -> RrfFusion.fuse(List.of(
                    bm25Retriever.retrieve(novel.id(), bq.query(), 30),
                    vectorRetriever.retrieve(novel.id(), bq.query(), 30)), 60, TOP_K);
            case "rrf-rerank" -> hybrid.retrieve(novel.id(), bq.query());
            case "multi-query" -> multiQuery(bq);
            default -> throw new IllegalArgumentException(method);
        };
    }

    /** MultiQuery：标注 query + 固定人物 query + 该 query 语义 thread（用小说断点 thread 代替）。 */
    private static List<RetrievalResult> multiQuery(BenchmarkQuery bq) {
        List<String> queries = new ArrayList<>();
        queries.add(bq.query());
        queries.add(MULTI_CHARACTER_QUERY);
        // thread：用标注 query 首个 gold 章节的未解决线索（若有）
        List<RetrievalQuery> built = queryBuilder.build(novel);
        built.stream().filter(q -> "thread".equals(q.type())).findFirst()
                .ifPresent(q -> queries.add(q.text()));
        return hybrid.retrieveMulti(novel.id(), queries);
    }

    /** Baseline：最近 3 章原文窗口（chapter 口径候选）。 */
    private static List<RetrievalResult> baselineCandidates() {
        List<RetrievalResult> results = new ArrayList<>();
        for (int i = Math.max(0, novel.chapterCount() - 3); i < novel.chapterCount(); i++) {
            int ordinal = i;
            results.add(new RetrievalResult("baseline-" + ordinal, novel.id(), ordinal,
                    com.inkforge.retrieval.MemoryChunkType.SUMMARY, "baseline", "原文窗口", 1.0));
        }
        return results;
    }

    /** P2-Memory：最近 3 章摘要 + 最近事件候选。 */
    private static List<RetrievalResult> p2MemoryCandidates() {
        List<RetrievalResult> results = new ArrayList<>();
        Set<Integer> seen = new java.util.HashSet<>();
        for (int i = Math.max(0, novel.chapterCount() - 3); i < novel.chapterCount(); i++) {
            results.add(new RetrievalResult("p2-summary-" + i, novel.id(), i,
                    com.inkforge.retrieval.MemoryChunkType.SUMMARY, "p2", "摘要", 1.0));
            seen.add(i);
        }
        memoryRepository.findEvents(novel.id(), 5, true).forEach(event -> {
            if (seen.add(event.chapterOrdinal())) {
                results.add(new RetrievalResult("p2-event-" + event.chapterOrdinal(), novel.id(),
                        event.chapterOrdinal(), com.inkforge.retrieval.MemoryChunkType.EVENT,
                        "p2", event.title(), 1.0));
            }
        });
        return results;
    }

    private static List<RetrievalResult> concat(List<RetrievalResult> bm25, List<RetrievalResult> vector) {
        Map<String, RetrievalResult> merged = new LinkedHashMap<>();
        bm25.forEach(r -> merged.putIfAbsent(r.chunkId(), r));
        vector.forEach(r -> merged.putIfAbsent(r.chunkId(), r));
        return merged.values().stream().limit(TOP_K).toList();
    }

    private static String renderTable(Map<String, List<Result>> chunk, Map<String, List<Result>> chapter) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Method | Recall@5 (chunk/ch) | Recall@10 (chunk/ch) | MRR@10 (chunk/ch) | NDCG@10 (chunk/ch) | Useful@8 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (String method : methods()) {
            Result c = Metrics.average(chunk.get(method));
            Result h = Metrics.average(chapter.get(method));
            sb.append(String.format("| %s | %.3f / %.3f | %.3f / %.3f | %.3f / %.3f | %.3f / %.3f | %.3f |%n",
                    method, c.recall5(), h.recall5(), c.recall10(), h.recall10(),
                    c.mrr10(), h.mrr10(), c.ndcg10(), h.ndcg10(), c.useful8()));
        }
        sb.append("\n环境：Java 21 · Spring Boot 4.1 · Lucene 9.12.3(smartcn BM25 默认k1/b) · MockEmbedding(1024维 n-gram 伪向量)\n");
        sb.append("固定参数：bm25-top-30 / vector-top-30 / fusion-top-30 / rerank-top-8 / rrf-k=60 / reranker=passthrough\n");
        sb.append("口径：chunk 命中=(chapter,memoryType) 双匹配；chapter 命中=仅章节匹配。baseline/p2-memory 无排名语义（MRR/NDCG 供参考）。\n");
        return sb.toString();
    }
}
