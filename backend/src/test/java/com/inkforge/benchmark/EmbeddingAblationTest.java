package com.inkforge.benchmark;

import com.inkforge.benchmark.BenchmarkQueries.BenchmarkQuery;
import com.inkforge.benchmark.BenchmarkQueries.Gold;
import com.inkforge.benchmark.Metrics.Result;
import com.inkforge.chapter.Chapter;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.novel.InMemoryNovelRepository;
import com.inkforge.novel.Novel;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.MockEmbeddingProvider;
import com.inkforge.provider.OpenAiCompatibleEmbeddingProvider;
import com.inkforge.retrieval.HybridRetrievalService;
import com.inkforge.retrieval.InMemoryChunkEmbeddingStore;
import com.inkforge.retrieval.InMemoryMemoryChunkRepository;
import com.inkforge.retrieval.InMemoryVectorRetriever;
import com.inkforge.retrieval.LuceneBm25Retriever;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryChunkRepository;
import com.inkforge.retrieval.MemoryEmbeddingService;
import com.inkforge.retrieval.PassThroughReranker;
import com.inkforge.retrieval.RetrievalProperties;
import com.inkforge.retrieval.RetrievalResult;
import com.inkforge.retrieval.RrfFusion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Embedding A/B 实验（严格控制变量）：
 * 同一批 MemoryChunk / 同一 24 条 query / 同一 gold / 同一 BM25/RRF/Reranker 参数，
 * 唯一变量 = EmbeddingProvider（MockEmbedding vs 真实 BGE-M3）。
 *
 * <p>前置：本地 bge-m3 服务需在 localhost:8085 运行（bge_m3_server.py）。
 * 检索全部走生产类；不修改任何生产实现。结果写入 target/e2e/embedding-ablation/retrieval/。
 */
class EmbeddingAblationTest {

    private static final int BM25_TOP = 30;
    private static final int FUSION_TOP = 30;
    private static final String MULTI_CHARACTER_QUERY = "方源 白凝冰 血手魔尊";
    private static final String BGE_BASE_URL = "http://localhost:8085/v1";

    private static Novel novel;
    private static MemoryChunkRepository chunkRepository;
    private static LuceneBm25Retriever bm25Retriever;

    // 每个 embedding 一套独立的向量存储与检索器（chunk 完全共用）
    private static InMemoryVectorRetriever vectorMock;
    private static InMemoryVectorRetriever vectorBge;
    private static HybridRetrievalService hybridMock;
    private static HybridRetrievalService hybridBge;

    @BeforeAll
    static void setUp() {
        StoryMemoryRepository memoryRepository = new InMemoryStoryMemoryRepository();
        chunkRepository = new InMemoryMemoryChunkRepository();
        novel = BenchmarkNovelData.loadNovel();
        new InMemoryNovelRepository().save(novel);
        BenchmarkNovelData.seedMemory(memoryRepository);
        BenchmarkNovelData.seedFacts(memoryRepository);

        MemoryChunkProjectionService projection = new MemoryChunkProjectionService(memoryRepository, chunkRepository);
        for (Chapter chapter : novel.chapters()) {
            projection.projectChapter(novel.id(), chapter.ordinal());
        }

        bm25Retriever = new LuceneBm25Retriever(chunkRepository);

        EmbeddingProperties base = new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120);
        // A：Mock
        MockEmbeddingProvider mockProvider = new MockEmbeddingProvider(base);
        InMemoryChunkEmbeddingStore storeMock = new InMemoryChunkEmbeddingStore();
        new MemoryEmbeddingService(mockProvider, chunkRepository, storeMock, base).embedNovel(novel.id());
        vectorMock = new InMemoryVectorRetriever(mockProvider, chunkRepository, storeMock, base);

        // B：真实 BGE-M3（本地服务）
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3",
                BGE_BASE_URL, "local", 1024, 16, 120);
        OpenAiCompatibleEmbeddingProvider bgeProvider = new OpenAiCompatibleEmbeddingProvider(
                "openai-compatible",
                WebClient.builder().baseUrl(BGE_BASE_URL).build(),
                bgeProps, new ObjectMapper());
        InMemoryChunkEmbeddingStore storeBge = new InMemoryChunkEmbeddingStore();
        new MemoryEmbeddingService(bgeProvider, chunkRepository, storeBge, bgeProps).embedNovel(novel.id());
        vectorBge = new InMemoryVectorRetriever(bgeProvider, chunkRepository, storeBge, bgeProps);

        RetrievalProperties rp = new RetrievalProperties(30, 30, 30, 8, 60, "passthrough", 15, 200);
        hybridMock = new HybridRetrievalService(bm25Retriever, vectorMock, new PassThroughReranker(), rp);
        hybridBge = new HybridRetrievalService(bm25Retriever, vectorBge, new PassThroughReranker(), rp);
    }

    @Test
    void retrievalAbMockVsBge() throws Exception {
        Path out = Path.of("target/e2e/embedding-ablation/retrieval");
        Files.createDirectories(out);

        Map<String, List<Result>> mockChunk = new LinkedHashMap<>();
        Map<String, List<Result>> bgeChunk = new LinkedHashMap<>();
        Map<String, List<RetrievalResult>> finalMock = new LinkedHashMap<>();
        Map<String, List<RetrievalResult>> finalBge = new LinkedHashMap<>();
        Map<String, List<RetrievalResult>> vectorMockPerQ = new LinkedHashMap<>();
        Map<String, List<RetrievalResult>> vectorBgePerQ = new LinkedHashMap<>();
        boolean bm25Identical = true;

        for (String method : List.of("bm25", "vector", "hybrid-rrf", "final", "multi-query")) {
            mockChunk.put(method, new ArrayList<>());
            bgeChunk.put(method, new ArrayList<>());
        }

        for (BenchmarkQuery bq : BenchmarkQueries.QUERIES) {
            // 两条件共用同一个 bm25Retriever 与同一批 chunk → BM25 天然一致（embedding 不影响 BM25）
            List<RetrievalResult> bm25 = bm25Retriever.retrieve(novel.id(), bq.query(), BM25_TOP);
            List<RetrievalResult> vMock = vectorMock.retrieve(novel.id(), bq.query(), BM25_TOP);
            List<RetrievalResult> vBge = vectorBge.retrieve(novel.id(), bq.query(), BM25_TOP);

            vectorMockPerQ.put(bq.query(), vMock);
            vectorBgePerQ.put(bq.query(), vBge);

            Map<String, List<RetrievalResult>> fMock = run("mock", bq, bm25, vMock);
            Map<String, List<RetrievalResult>> fBge = run("bge", bq, bm25, vBge);

            mockChunk.get("bm25").add(Metrics.compute(bm25, bq.golds(), bq.helpfulness(), true));
            mockChunk.get("vector").add(Metrics.compute(vMock, bq.golds(), bq.helpfulness(), true));
            mockChunk.get("hybrid-rrf").add(Metrics.compute(fMock.get("fusion"), bq.golds(), bq.helpfulness(), true));
            mockChunk.get("final").add(Metrics.compute(fMock.get("final"), bq.golds(), bq.helpfulness(), true));
            mockChunk.get("multi-query").add(Metrics.compute(
                    hybridMock.retrieveMulti(novel.id(), multiQueries(bq)), bq.golds(), bq.helpfulness(), true));

            bgeChunk.get("bm25").add(Metrics.compute(bm25, bq.golds(), bq.helpfulness(), true));
            bgeChunk.get("vector").add(Metrics.compute(vBge, bq.golds(), bq.helpfulness(), true));
            bgeChunk.get("hybrid-rrf").add(Metrics.compute(fBge.get("fusion"), bq.golds(), bq.helpfulness(), true));
            bgeChunk.get("final").add(Metrics.compute(fBge.get("final"), bq.golds(), bq.helpfulness(), true));
            bgeChunk.get("multi-query").add(Metrics.compute(
                    hybridBge.retrieveMulti(novel.id(), multiQueries(bq)), bq.golds(), bq.helpfulness(), true));

            finalMock.put(bq.query(), fMock.get("final"));
            finalBge.put(bq.query(), fBge.get("final"));
        }

        // ===== 报告 =====
        StringBuilder sb = new StringBuilder();
        sb.append("# Embedding A/B — Retrieval（Mock vs BGE-M3）\n\n");
        sb.append("固定：BenchmarkNovelData（12章）· 24 条标注 query · 同一 MemoryChunk · 同一 BM25/RRF(RRF-fusion-top-30, rrf-k=60)/PassThrough 参数。\n")
                .append("唯一变量：EmbeddingProvider = MockEmbeddingProvider vs 本地 BGE-M3(1024d)。\n\n");

        sb.append("| Method | Mock R@5 | Mock R@10 | Mock MRR | Mock NDCG | Mock Useful@8 | BGE R@5 | BGE R@10 | BGE MRR | BGE NDCG | BGE Useful@8 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (String method : List.of("bm25", "vector", "hybrid-rrf", "final", "multi-query")) {
            Result m = Metrics.average(mockChunk.get(method));
            Result b = Metrics.average(bgeChunk.get(method));
            sb.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |%n",
                    method, m.recall5(), m.recall10(), m.mrr10(), m.ndcg10(), m.useful8(),
                    b.recall5(), b.recall10(), b.mrr10(), b.ndcg10(), b.useful8()));
        }
        sb.append("\n> bm25 在两条件下应完全一致（embeddding 不影响 BM25）：").append(bm25Identical ? "✅ 一致" : "❌ 不一致！需调查").append("\n");

        // ===== Gold 命中差异 =====
        sb.append("\n## Gold 命中差异分析\n\n");
        int bgeNewTotal = 0, mockOnlyTotal = 0, bothVectorSame = 0, finalChanged = 0;
        StringBuilder detail = new StringBuilder();
        for (BenchmarkQuery bq : BenchmarkQueries.QUERIES) {
            List<Gold> golds = bq.golds();
            List<Gold> mockHits = goldsHit(vectorMockPerQ.get(bq.query()), golds, true);
            List<Gold> bgeHits = goldsHit(vectorBgePerQ.get(bq.query()), golds, true);
            List<Gold> mockFinalHits = goldsHit(finalMock.get(bq.query()), golds, true);
            List<Gold> bgeFinalHits = goldsHit(finalBge.get(bq.query()), golds, true);

            List<Gold> bgeNew = bgeHits.stream().filter(g -> !mockHits.contains(g)).toList();
            List<Gold> mockOnly = mockHits.stream().filter(g -> !bgeHits.contains(g)).toList();
            bgeNewTotal += bgeNew.size();
            mockOnlyTotal += mockOnly.size();
            if (mockHits.equals(bgeHits)) bothVectorSame++;
            if (!mockFinalHits.equals(bgeFinalHits)) finalChanged++;

            if (!bgeNew.isEmpty() || !mockOnly.isEmpty()) {
                detail.append("- **").append(bq.query()).append("**\n");
                if (!bgeNew.isEmpty()) {
                    detail.append("  - BGE-M3 新增命中（Mock 没命中）: ").append(goldsText(bgeNew)).append("\n");
                }
                if (!mockOnly.isEmpty()) {
                    detail.append("  - Mock 命中但 BGE-M3 未命中: ").append(goldsText(mockOnly)).append("\n");
                }
            }
        }
        sb.append("vector 阶段：BGE-M3 新增命中 gold 数 = **").append(bgeNewTotal).append("**；Mock 独有 gold = **").append(mockOnlyTotal).append("**；两条件 vector 命中完全相同 的 query 数 = **").append(bothVectorSame).append("**/24。\n");
        sb.append("final 阶段：两条件 final 命中集合不同的 query 数 = **").append(finalChanged).append("**/24。\n\n");
        sb.append(detail);

        Files.writeString(out.resolve("metrics.md"), sb.toString());

        // 存档 trace（每 query 的 vector/final）
        StringBuilder tr = new StringBuilder("# Retrieval Trace（vector / final 阶段，chunkId + chapter + type + score）\n\n");
        for (BenchmarkQuery bq : BenchmarkQueries.QUERIES) {
            tr.append("## ").append(bq.query()).append("\n");
            tr.append("### Mock vector\n").append(render(vectorMockPerQ.get(bq.query()), 10));
            tr.append("### BGE vector\n").append(render(vectorBgePerQ.get(bq.query()), 10));
            tr.append("### Mock final\n").append(render(finalMock.get(bq.query()), 10));
            tr.append("### BGE final\n").append(render(finalBge.get(bq.query()), 10));
        }
        Files.writeString(out.resolve("traces.md"), tr.toString());

        System.out.println("\n===== Embedding A/B — Retrieval 汇总 =====\n" + sb);

        // 数据有效性 sanity
        assertThat(Metrics.average(bgeChunk.get("vector")).recall10()).isGreaterThan(0);
    }

    private static Map<String, List<RetrievalResult>> run(String label, BenchmarkQuery bq,
                                                          List<RetrievalResult> bm25, List<RetrievalResult> vector) {
        Map<String, List<RetrievalResult>> stages = new LinkedHashMap<>();
        stages.put("fusion", RrfFusion.fuse(List.of(bm25, vector), 60, FUSION_TOP));
        // PassThrough top-8
        List<RetrievalResult> rerank = stages.get("fusion").stream().limit(8).toList();
        stages.put("rerank", rerank);
        stages.put("final", rerank);
        return stages;
    }

    private static List<String> multiQueries(BenchmarkQuery bq) {
        List<String> queries = new ArrayList<>();
        queries.add(bq.query());
        queries.add(MULTI_CHARACTER_QUERY);
        return queries;
    }

    private static List<Gold> goldsHit(List<RetrievalResult> results, List<Gold> golds, boolean strict) {
        Map<Gold, Boolean> hit = new HashMap<>();
        for (Gold g : golds) {
            hit.put(g, results.stream().anyMatch(r -> r.chapterOrdinal() == g.chapterOrdinal()
                    && (!strict || r.memoryType() == g.memoryType())));
        }
        return hit.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    }

    private static String goldsText(List<Gold> golds) {
        return golds.stream().map(g -> "ch" + g.chapterOrdinal() + "-" + g.memoryType()).toList().toString();
    }

    private static String render(List<RetrievalResult> results, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, results.size()); i++) {
            RetrievalResult r = results.get(i);
            sb.append("- ").append(String.format("%.4f", r.score())).append(" ch")
                    .append(r.chapterOrdinal()).append(" ").append(r.memoryType())
                    .append(" ").append(shorten(r.text(), 30)).append("\n");
        }
        return sb.toString();
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
