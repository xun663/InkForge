package com.inkforge.retrieval;

import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-B3-0：retrieved-memory Context Selection 两条策略的离线行为测试。
 *
 * <p>A = 当前生产 fitTail（超预算裁头保尾）；B = Rank-Preserving（保序累加，放不下即停）。
 * 合成、零 LLM；用于锁定两条策略的语义（§十六 1-7）。
 */
class RetrievalSelectionSimTest {

    private final TokenCounter tc = new JtokkitTokenCounter();

    private static RetrievalResult res(String chunkId, int chapter, String text) {
        // chapter 参数即显示章节号；ordinal = chapter-1 → 渲染 "第N章"
        return new RetrievalResult(chunkId, "n1", chapter - 1, MemoryChunkType.EVENT,
                "src:" + chunkId, text, 0.9 - chapter / 100.0);
    }

    /** 10 个等长 chunk，rank 1..10 即 chapter 1..10。 */
    private static List<RetrievalResult> tenEqualChunks(int charsEach) {
        List<RetrievalResult> list = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            list.add(res("c" + i, i, ("第" + i + "章 关系/伏笔片段，被检索到的早期证据正文。".repeat(charsEach / 10) + "x")));
        }
        return list;
    }

    private int bodyTokens(List<RetrievalResult> results) {
        return tc.count(RetrievalSelectionSim.joinBody(results));
    }

    // ---- 1. fitTail 行为：超预算 → 裁头保尾 ----
    @Test
    void fitTailKeepsTailAndDropsHeadWhenOverBudget() {
        List<RetrievalResult> rs = tenEqualChunks(40);
        int full = bodyTokens(rs);
        int third = full / 3;
        String kept = RetrievalSelectionSim.selectTail(rs, third, tc);

        assertThat(kept).isNotEqualTo(RetrievalSelectionSim.joinBody(rs)); // 确实被裁
        // 头部（高排名，chapter1/2/3）应被裁掉
        assertThat(RetrievalSelectionSim.chapterPresent(kept, 1)).isFalse();
        assertThat(RetrievalSelectionSim.chapterPresent(kept, 2)).isFalse();
        assertThat(RetrievalSelectionSim.chapterPresent(kept, 3)).isFalse();
        // 尾部（低排名 chapter10）应被保留
        assertThat(RetrievalSelectionSim.chapterPresent(kept, 10)).isTrue();
    }

    @Test
    void fitTailUnderBudgetKeepsEverything() {
        List<RetrievalResult> rs = tenEqualChunks(40);
        String kept = RetrievalSelectionSim.selectTail(rs, bodyTokens(rs) + 200, tc);
        assertThat(RetrievalSelectionSim.chapterPresent(kept, 1)).isTrue();
        assertThat(RetrievalSelectionSim.chapterPresent(kept, 10)).isTrue();
        assertThat(RetrievalSelectionSim.chaptersPresent(kept)).hasSize(10);
    }

    // ---- 2/4/7. 高排名 gold 保留：A 丢 rank1，B 保 rank1 ----
    @Test
    void tailDropsHighRankGoldWhileRankPreservingKeepsIt() {
        List<RetrievalResult> rs = tenEqualChunks(40);
        int full = bodyTokens(rs);
        int third = full / 3;

        String a = RetrievalSelectionSim.selectTail(rs, third, tc);              // 生产：保尾
        String b = RetrievalSelectionSim.selectRankPreserving(rs, third, tc);    // 保序

        // rank1 gold：生产 fitTail 把它裁掉；Rank-Preserving 保留
        assertThat(RetrievalSelectionSim.chapterPresent(a, 1)).isFalse();
        assertThat(RetrievalSelectionSim.chapterPresent(b, 1)).isTrue();
        // rank2 同理保留
        assertThat(RetrievalSelectionSim.chapterPresent(b, 2)).isTrue();
    }

    // ---- 3. Rank-Preserving：放不下即停，不删前面已加入 ----
    @Test
    void rankPreservingStopsAtBudgetAndKeepsPrefixOrder() {
        List<RetrievalResult> rs = tenEqualChunks(40);
        int full = bodyTokens(rs);
        int third = full / 3;

        String b = RetrievalSelectionSim.selectRankPreserving(rs, third, tc);
        // 保序前缀：前面 chapter 在、很靠后的 chapter 不在
        assertThat(RetrievalSelectionSim.chapterPresent(b, 1)).isTrue();
        assertThat(RetrievalSelectionSim.chapterPresent(b, 2)).isTrue();
        assertThat(RetrievalSelectionSim.chapterPresent(b, 10)).isFalse();
        // 不应出现高排名被丢、只留尾部的情况
        assertThat(RetrievalSelectionSim.chapterPresent(b, 9)).isFalse();
    }

    // ---- 5. 单个超长 rank1 chunk：不清除、不静默丢 ----
    @Test
    void oversizedSingleTopChunkIsTruncatedNotDropped() {
        // rank1 超长（> 预算），其余很小
        List<RetrievalResult> rs = List.of(
                res("big", 1, "第1章 超长早期关系证据正文。".repeat(300)),
                res("s2", 2, "第2章 短片段。"),
                res("s3", 3, "第3章 短片段。"));
        // 预算取"能容下省略标记 + 一个短行"，但远小于超长 rank1 → 兜底截断被触发，而非整条丢弃
        int budget = tc.count(RetrievalSelectionSim.OMITTED_MARK)
                + tc.count("· 第2章 · [EVENT] 第2章 短片段。") + 10;

        String b = RetrievalSelectionSim.selectRankPreserving(rs, budget, tc);
        // rank1 没有被整体丢弃：发生截断（省略标记出现）且其文本尾部仍在
        assertThat(b).isNotBlank();
        assertThat(b).contains(RetrievalSelectionSim.OMITTED_MARK);
        assertThat(b).endsWith("证据正文。");
        // rank2 放不下 → 停，未加入
        assertThat(RetrievalSelectionSim.chapterPresent(b, 2)).isFalse();
    }

    // ---- 4. 多 chunk 在预算内：B 保序全保 ----
    @Test
    void rankPreservingUnderBudgetKeepsAllInRankOrder() {
        List<RetrievalResult> rs = tenEqualChunks(40);
        int full = bodyTokens(rs);
        String b = RetrievalSelectionSim.selectRankPreserving(rs, full + 200, tc);
        assertThat(RetrievalSelectionSim.chaptersPresent(b)).hasSize(10);
        // 顺序仍是 1..10（chaptersPresent 按标记出现顺序去重返回）
        assertThat(RetrievalSelectionSim.chaptersPresent(b)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    // ---- 6. A/B 可复现（确定性） ----
    @Test
    void strategiesAreDeterministic() {
        List<RetrievalResult> rs = tenEqualChunks(40);
        int budget = bodyTokens(rs) / 3;
        assertThat(RetrievalSelectionSim.selectTail(rs, budget, tc))
                .isEqualTo(RetrievalSelectionSim.selectTail(rs, budget, tc));
        assertThat(RetrievalSelectionSim.selectRankPreserving(rs, budget, tc))
                .isEqualTo(RetrievalSelectionSim.selectRankPreserving(rs, budget, tc));
    }
}
