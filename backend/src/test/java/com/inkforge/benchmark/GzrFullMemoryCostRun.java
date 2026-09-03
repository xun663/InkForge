package com.inkforge.benchmark;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.MemoryUpdateService;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.extraction.ChapterExtractionResult;
import com.inkforge.memory.extraction.ExtractionValidator;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.OpenAiCompatibleLlmProvider;
import com.inkforge.provider.ProviderStreamEvent;
import com.inkforge.provider.TaskType;
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
import com.inkforge.retrieval.RetrievalQuery;
import com.inkforge.retrieval.RetrievalQueryBuilder;
import com.inkforge.retrieval.RetrievalResult;
import com.inkforge.retrieval.RetrievalSelectionSim;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蛊真人「全量记忆 + 结尾续写」成本测试（磁盘检查点、可分批续跑、deepseek-v4-flash）。
 *
 * <ul>
 *   <li>源文件为 EPUB 排版：前 ~238 行制作说明/目录，正文以「第…节」分节（号在卷间重置），共 2335 节
 *       ≈ 7.3M 字符、avg ~3.1k/节。项目解析器不认「节」→ 本 harness 自行按节切分并重建 Chapter(第N章/节)。</li>
 *   <li><b>检查点</b>：每节提取结果写 {@code <temp>/gzr_cost/outcomes/chapter-&lt;k&gt;.json}
 *       （{@link ChapterExtractionResult} JSON + 估算 tokens + 耗时）；已成功则跳过 → 可停可续、跨会话安全。</li>
 *   <li><b>分批</b>：{@code -DGZR_MAX=n} 一次最多处理 n 节（默认 300），跑完一批再跑一批。</li>
 *   <li><b>并行</b>：{@code -DGZR_PAR=n}（默认 1）。实测：单路 LLM ≈12s/章；4 路约 3×、≈12 章/分钟。
 *       只并行写 outcomes；重放合并记忆仍按 ordinal 串行。</li>
 *   <li><b>成本计量</b>：用 CountingLlmProvider 装饰 deepseek，逐次估算输入/输出 tokens（jtokkit），累计写 stats。</li>
 *   <li><b>buildAndContinue</b>：全部提取完成后，把结果重放建记忆→投影→BGE 嵌入→检索→末节后续写。</li>
 * </ul>
 * key 只从环境变量读；不写入代码/日志/git。
 */
class GzrFullMemoryCostRun {

    private static final String SOURCE = "C:/Users/xun/Desktop/训练材料/《蛊真人》（精排精校初版未删改）【作者：蛊真人】1.txt";
    private static final String TEMP = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e";
    private static final String BGE_BASE = "http://localhost:8085/v1";
    /** 检查点目录：默认 TEMP 下（易被清理）；可 -DGZR_DIR=<稳定路径> 指定，便于长期复用不重跑。 */
    private static final Path CKPT = Path.of(System.getProperty("GZR_DIR", TEMP + "/gzr_cost"));
    private static final Path OUTCOMES = CKPT.resolve("outcomes");

    private static final Pattern SEC = Pattern.compile("^第[零〇一二两三四五六七八九十百千0-9]+节[:：]?(.*)$");

    /** 分节：跳过源文件头部的制作说明/目录，直至第一个正文「节」行。 */
    static List<String[]> sections() throws Exception {
        List<String> lines = List.of(Files.readString(Path.of(SOURCE), StandardCharsets.UTF_8).split("\n"));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (SEC.matcher(lines.get(i).strip()).matches()) { start = i; break; }
        }
        if (start < 0) throw new IllegalStateException("未找到正文分节起点");
        List<String[]> out = new ArrayList<>();
        StringBuilder sb = null;
        String title = null;
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            Matcher m = SEC.matcher(l);
            if (m.matches()) {
                if (sb != null) out.add(new String[]{title, sb.toString().trim()});
                title = m.group(1).strip();
                sb = new StringBuilder();
            } else if (sb != null && !l.isEmpty()) {
                sb.append(l).append('\n');
            }
        }
        if (sb != null) out.add(new String[]{title, sb.toString().trim()});
        return out;
    }

    private static Chapter chapter(int idx, String[] sec) {
        return new Chapter(idx, idx + 1, sec[0], sec[1]);
    }

    private static MemoryExtractionProperties props() {
        return new MemoryExtractionProperties(3, 12000, 2048, 4096, 0.2, 2, 0.7, 300, 200);
    }

    // ================= 1) 分批提取 =================
    @Test
    void extractBatch() throws Exception {
        Files.createDirectories(OUTCOMES);
        int max = Integer.parseInt(System.getProperty("GZR_MAX", "300"));
        int par = Integer.parseInt(System.getProperty("GZR_PAR", "1"));
        List<String[]> secs = sections();
        ObjectMapper om = new ObjectMapper();
        TokenCounter tc = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();

        int total = Math.min(max, secs.size());
        List<Integer> pending = new ArrayList<>();
        int skip = 0;
        for (int k = 0; k < total; k++) {
            Path f = OUTCOMES.resolve("chapter-" + k + ".json");
            Map<?, ?> prev = readOutcome(om, f);
            if (prev == null) { pending.add(k); continue; }                   // 缺失或损坏 → 重做（原子覆盖）
            if (Boolean.TRUE.equals(prev.get("ok"))) skip++;                  // 已成功 → 跳过
            else if (num(prev, "attempts") >= 3) skip++;                      // 重试≥3 仍败 → 永久跳过
            else pending.add(k);                                              // 失败待重试
        }

        // 每个 worker 独享 CountingLlm + MemoryExtractor（计数隔离；底层 deepseek 单例共享）
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(par);
        int per = (pending.size() + par - 1) / par;
        List<java.util.concurrent.Future<long[]>> futures = new ArrayList<>();
        for (int w = 0; w < par; w++) {
            final int from = w * per, to = Math.min(pending.size(), from + per);
            if (from >= to) continue;
            futures.add(pool.submit(() -> {
                String apiKey = key();
                String model = model();
                LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                        WebClient.builder().baseUrl("https://api.deepseek.com").build(),
                        new LlmProperties("deepseek", "https://api.deepseek.com", apiKey, model, 300, new LlmProperties.Mock(0)), om);
                CountingLlm counting = new CountingLlm(deepseek, tc);
                MemoryExtractor ex = new MemoryExtractor(counting, catalog, tc, new ExtractionValidator(), props(), om);
                long in = 0, out = 0, ms = 0; int done = 0, fail = 0;
                for (int idx = from; idx < to; idx++) {
                    int k = pending.get(idx);
                    Chapter ch = chapter(k, secs.get(k));
                    counting.reset();
                    long t0 = System.currentTimeMillis();
                    var outcome = ex.extract(ch, "第" + (k + 1) + "节 " + ch.title());
                    long el = System.currentTimeMillis() - t0;
                    int attempts = 1;
                    Path f = OUTCOMES.resolve("chapter-" + k + ".json");
                    Map<?, ?> prev = readOutcome(om, f);
                    if (prev != null) attempts = (int) num(prev, "attempts") + 1;
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("ok", outcome.result() != null);
                    rec.put("attempts", attempts);
                    rec.put("inTokens", counting.lastIn());
                    rec.put("outTokens", counting.lastOut());
                    rec.put("ms", el);
                    if (outcome.result() != null) rec.put("result", om.writeValueAsString(outcome.result()));
                    else rec.put("error", outcome.errorMessage());
                    atomicWrite(f, om, rec);
                    in += counting.lastIn(); out += counting.lastOut(); ms += el;
                    if (outcome.result() != null) done++; else fail++;
                    System.out.printf("gzr[par=%d] ch=%d done=%d fail=%d in=%d out=%d%n", par, k, done, fail,
                            counting.lastIn(), counting.lastOut());
                }
                return new long[]{done, fail, in, out, ms};
            }));
        }
        pool.shutdown();
        long in = 0, out = 0, ms = 0; int done = 0, fail = 0;
        for (var f : futures) {
            long[] r = f.get();
            done += (int) r[0]; fail += (int) r[1]; in += r[2]; out += r[3]; ms += r[4];
        }
        System.out.printf("BATCH DONE par=%d total=%d pending=%d done=%d fail=%d skip=%d inTok=%d outTok=%d elapsed=%.1fs%n",
                par, total, pending.size(), done, fail, skip, in, out, ms / 1000.0);
        writeStats();
    }

    // ================= 2) 重放建记忆 + 结尾续写 =================
    @Test
    void buildAndContinue() throws Exception {
        List<String[]> secs = sections();
        List<String[]> done = new ArrayList<>();
        ObjectMapper om = new ObjectMapper();
        int corrupt = 0;
        List<Integer> corruptIdx = new ArrayList<>();
        for (int k = 0; k < secs.size(); k++) {
            Path f = OUTCOMES.resolve("chapter-" + k + ".json");
            Map<?, ?> rec = readOutcome(om, f);
            if (rec == null) {
                if (Files.exists(f)) { corrupt++; corruptIdx.add(k); }   // 损坏 → 跳过并提示，不崩
                continue;
            }
            if (Boolean.TRUE.equals(rec.get("ok"))) done.add(secs.get(k));
        }
        System.out.println("gzr 可重放章节: " + done.size() + "/" + secs.size() + " 损坏文件: " + corrupt);
        if (!corruptIdx.isEmpty()) System.out.println("gzr 损坏章节(需重做提取): " + corruptIdx);
        // —— build 记忆 ——
        StoryMemoryRepository mem = new InMemoryStoryMemoryRepository();
        MemoryChunkRepository chunks = new InMemoryMemoryChunkRepository();
        MemoryUpdateService up = new MemoryUpdateService(mem, props());
        MemoryChunkProjectionService proj = new MemoryChunkProjectionService(mem, chunks);
        List<Chapter> chapters = new ArrayList<>();
        int okc = 0;
        for (int k = 0; k < secs.size(); k++) {
            Path f = OUTCOMES.resolve("chapter-" + k + ".json");
            Map<?, ?> rec = readOutcome(om, f);
            if (rec == null || !Boolean.TRUE.equals(rec.get("ok"))) continue;
            Object res = rec.get("result");
            if (!(res instanceof String json)) continue;
            ChapterExtractionResult r = om.readValue(json, ChapterExtractionResult.class);
            Chapter ch = chapter(k, secs.get(k));
            chapters.add(ch);
            up.apply("gzr", ch, r);
            proj.projectChapter("gzr", ch.ordinal());
            okc++;
        }
        System.out.println("gzr 记忆构建章节: " + okc + "（skip 未提取/损坏节）");
        // —— 续写上下文（末节断点）——
        String apiKey = key();
        String model = model();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        TokenCounter tc = new JtokkitTokenCounter();
        LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                WebClient.builder().baseUrl("https://api.deepseek.com").build(),
                new LlmProperties("deepseek", "https://api.deepseek.com", apiKey, model, 300, new LlmProperties.Mock(0)), om);
        if (chapters.isEmpty()) { System.out.println("无记忆可续写"); return; }
        continueWith(novel("gzr", chapters), mem, chunks, deepseek, catalog, tc, om);
    }

    private static void continueWith(Novel novel, StoryMemoryRepository mem, MemoryChunkRepository chunks,
                                     LlmProvider deepseek, PromptCatalog catalog, TokenCounter tc, ObjectMapper om)
            throws Exception {
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);
        var bge = new com.inkforge.provider.OpenAiCompatibleEmbeddingProvider("openai-compatible",
                WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, om);
        InMemoryChunkEmbeddingStore store = new InMemoryChunkEmbeddingStore();
        new MemoryEmbeddingService(bge, chunks, store, bgeProps).embedNovel(novel.id());
        LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunks);
        InMemoryVectorRetriever vec = new InMemoryVectorRetriever(bge, chunks, store, bgeProps);
        RetrievalProperties rp = new RetrievalProperties(30, 30, 30, 30, 60, "passthrough", 15, 200);
        HybridRetrievalService hy = new HybridRetrievalService(bm25, vec, new PassThroughReranker(), rp);
        List<RetrievalQuery> queries = new RetrievalQueryBuilder(mem).build(novel);
        Map<String, RetrievalResult> best = new LinkedHashMap<>();
        for (RetrievalQuery q : queries) {
            if (q.text() == null || q.text().isBlank()) continue;
            for (RetrievalResult r : hy.retrieve(novel.id(), q.text())) best.merge(r.chunkId(), r, (a, b) -> a.score() >= b.score() ? a : b);
        }
        List<RetrievalResult> finals = best.values().stream().sorted(Comparator.comparingDouble(RetrievalResult::score).reversed()).toList();
        int bodyBudget = 989;
        String retrieved = finals.isEmpty() ? ""
                : RetrievalSelectionSim.SECTION_HEADER + "\n" + RetrievalSelectionSim.selectRankPreserving(finals, bodyBudget, tc);
        Chapter last = novel.lastChapter();
        String recent = "【第" + last.chapterNo() + "节 " + (last.title() == null ? "" : last.title()) + "】\n" + last.content();
        String system = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(), "chapterNo", String.valueOf(last.chapterNo()), "chapterTitle", last.title() == null ? "" : last.title()));
        String user = catalog.render("continuation.memory.user.txt", Map.of(
                "sections", retrieved.isBlank() ? recent : recent + "\n\n" + retrieved));
        List<ChatMessage> msgs = List.of(ChatMessage.system(system), ChatMessage.user(user));
        Files.writeString(CKPT.resolve("context-final.txt"), "【system】\n" + system + "\n\n【user】\n" + user);
        LlmResponse resp = deepseek.complete(new LlmRequest(msgs, 1000, 0.8, deepseek.defaultModel(), TaskType.CONTINUATION));
        String gen = resp == null || resp.content() == null ? "" : resp.content();
        Files.writeString(CKPT.resolve("generation-final.txt"), gen);
        Files.writeString(CKPT.resolve("trace-final.txt"), "queries=" + queries.stream().map(RetrievalQuery::text).toList()
                + "\nretrieved=" + finals.size() + " top: " + (finals.isEmpty() ? "" : finals.stream().limit(8).map(r ->
                String.format("%.3f ch%s %s %s", r.score(), r.chapterOrdinal() + 1, r.memoryType(),
                        r.text() == null ? "" : r.text().length() > 50 ? r.text().substring(0, 50) : r.text())).toList()));
        System.out.println("续写完成 → " + CKPT.resolve("generation-final.txt"));
    }

    private static Novel novel(String id, List<Chapter> chapters) {
        return new Novel(id, "蛊真人", "gzr", chapters);
    }

    // ================= 3) 覆盖完整性报告（复用前校验） =================
    @Test
    void coverageReport() throws Exception {
        ObjectMapper om = new ObjectMapper();
        int total = sections().size();
        StringBuilder sb = new StringBuilder("gzr coverage total=" + total + "\n");
        int ok = 0, fail = 0, perm = 0, corrupt = 0;
        java.util.TreeSet<Integer> missing = new java.util.TreeSet<>();
        java.util.TreeSet<Integer> corruptSet = new java.util.TreeSet<>();
        java.util.TreeSet<Integer> permSet = new java.util.TreeSet<>();
        long in = 0, out = 0;
        for (int k = 0; k < total; k++) {
            Path f = OUTCOMES.resolve("chapter-" + k + ".json");
            Map<?, ?> r = readOutcome(om, f);
            if (r == null) {
                if (Files.exists(f)) { corrupt++; corruptSet.add(k); }   // 文件在但损坏 → 需重做
                else missing.add(k);
                continue;
            }
            in += num(r, "inTokens"); out += num(r, "outTokens");
            if (Boolean.TRUE.equals(r.get("ok"))) ok++;
            else { fail++; if (num(r, "attempts") >= 3) { perm++; permSet.add(k); } }
        }
        sb.append("ok=").append(ok).append(" fail=").append(fail)
          .append(" permanent(>=3)=").append(perm)
          .append(" corrupt=").append(corrupt)
          .append(" missing=").append(missing.size())
          .append(" inputTokens=").append(in).append(" outputTokens=").append(out)
          .append(" totalTokens=").append(in + out).append("\n");
        if (!missing.isEmpty()) sb.append("missing(前20): ").append(head(missing, 20)).append("\n");
        if (!corruptSet.isEmpty()) sb.append("corrupt(前20): ").append(head(corruptSet, 20)).append("\n");
        if (!permSet.isEmpty()) sb.append("permanent-fail: ").append(permSet).append("\n");
        Files.createDirectories(CKPT);
        Files.writeString(CKPT.resolve("coverage.txt"), sb.toString());
        System.out.print(sb);
    }

    private static String head(java.util.TreeSet<Integer> s, int n) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (Integer x : s) { if (i++ >= n) break; b.append(x).append(','); }
        return b.toString();
    }

    // ================= 统计 =================
    private static void writeStats() throws Exception {
        Files.createDirectories(CKPT);
        List<Path> files;
        try (var s = Files.list(OUTCOMES)) { files = s.filter(p -> p.getFileName().toString().startsWith("chapter-")).sorted().toList(); }
        long in = 0, out = 0, ms = 0; int ok = 0;
        ObjectMapper om = new ObjectMapper();
        for (Path f : files) {
            Map<?, ?> rec = readOutcome(om, f);
            if (rec == null) continue;
            in += num(rec, "inTokens");
            out += num(rec, "outTokens");
            ms += num(rec, "ms");
            if (Boolean.TRUE.equals(rec.get("ok"))) ok++;
        }
        Files.writeString(CKPT.resolve("stats.txt"),
                String.format("chaptersDone=%d inputTokens=%d outputTokens=%d totalTokens=%d elapsedMs=%d%n",
                        ok, in, out, in + out, ms));
    }

    private static long num(Map<?, ?> rec, String key) {
        Object v = rec.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** 原子写：先写 *.tmp 再 rename，避免进程中断留下半截 JSON。 */
    static void atomicWrite(Path f, ObjectMapper om, Map<String, Object> rec) throws Exception {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, om.writeValueAsString(rec));
        try {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 容错读：文件缺失/JSON 损坏 → 返回 null（调用方按"未完成/待重试"处理，绝不因单文件坏而崩整批）。 */
    static Map<?, ?> readOutcome(ObjectMapper om, Path f) {
        try {
            if (!Files.exists(f)) return null;
            return om.readValue(Files.readString(f), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String key() {
        String k = System.getenv("INKFORGE_LLM_API_KEY");
        if (k == null || k.isBlank()) k = System.getenv("LLM_API_KEY");
        if (k == null || k.isBlank()) throw new IllegalStateException("无 deepseek key");
        return k;
    }

    private static String model() {
        return System.getenv().getOrDefault("INKFORGE_LLM_MODEL", "deepseek-v4-flash");
    }

    /** 装饰 deepseek，估算每次 complete 的输入/输出 tokens（jtokkit）。 */
    static final class CountingLlm implements LlmProvider {
        private final LlmProvider delegate;
        private final TokenCounter tc;
        private final AtomicLong in = new AtomicLong(), out = new AtomicLong();

        CountingLlm(LlmProvider delegate, TokenCounter tc) { this.delegate = delegate; this.tc = tc; }

        void reset() { in.set(0); out.set(0); }

        long lastIn() { return in.get(); }
        long lastOut() { return out.get(); }

        @Override public String name() { return delegate.name(); }
        @Override public String defaultModel() { return delegate.defaultModel(); }

        @Override
        public LlmResponse complete(LlmRequest request) {
            long i = 0;
            for (ChatMessage m : request.messages()) i += tc.count(m.content());
            in.addAndGet(i);
            LlmResponse resp = delegate.complete(request);
            if (resp != null && resp.content() != null) out.addAndGet(tc.count(resp.content()));
            return resp;
        }

        @Override
        public Flux<ProviderStreamEvent> stream(LlmRequest request) {
            long i = 0;
            for (ChatMessage m : request.messages()) i += tc.count(m.content());
            in.addAndGet(i);
            return delegate.stream(request).doOnNext(e -> { });
        }
    }
}
