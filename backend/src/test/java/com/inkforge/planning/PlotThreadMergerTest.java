package com.inkforge.planning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6：PlotThread 确定性 upsert 规则 —— "LLM 建议，代码决定"；
 * 状态永不被规划结果改写；标题归一化匹配；章节字段钳制。
 */
class PlotThreadMergerTest {

    private final InMemoryPlotThreadRepository repository = new InMemoryPlotThreadRepository();
    private final PlotThreadMerger merger = new PlotThreadMerger(repository);

    private static EndingAnalysis.EndingThread thread(String title, String summary, Integer firstSeen) {
        return new EndingAnalysis.EndingThread(title, summary, "计划收束方式", firstSeen,
                List.of("林默"));
    }

    @Test
    void newThreadIsCreatedOpenWithClampedChapters() {
        List<PlotThread> saved = merger.merge("n1",
                List.of(thread("黑玉佩来源", "第 87 章埋下的伏笔", 87)), 12);

        assertThat(saved).hasSize(1);
        PlotThread created = saved.getFirst();
        assertThat(repository.findByNovelId("n1")).hasSize(1);
        assertThat(created.status()).isEqualTo(PlotThreadStatus.OPEN);
        assertThat(created.firstSeenChapter()).isEqualTo(12); // 超出 lastOrdinal → 钳到 12
        assertThat(created.lastSeenChapter()).isEqualTo(12);
        assertThat(created.summary()).isEqualTo("第 87 章埋下的伏笔");
    }

    @Test
    void whitespaceDifferenceMergesIntoExistingThread() {
        merger.merge("n1", List.of(thread("黑玉佩来源", "首次分析摘要", 3)), 5);
        List<PlotThread> second = merger.merge("n1",
                List.of(thread("黑玉佩  来源", "更新后的摘要", 9)), 10);

        assertThat(repository.findByNovelId("n1")).hasSize(1);
        PlotThread merged = second.getFirst();
        assertThat(merged.firstSeenChapter()).isEqualTo(3); // 保留首次出现章节
        assertThat(merged.lastSeenChapter()).isEqualTo(10);
        assertThat(merged.summary()).isEqualTo("更新后的摘要");
    }

    @Test
    void resolvedThreadIsNeverDowngradedByPlanning() {
        // 预置一条 RESOLVED 线索（未来显式收束能力的产物）
        PlotThread resolved = new PlotThread("t1", "n1", "旧线索", "已收束",
                PlotThreadStatus.RESOLVED, 0, 4, List.of(), null, null);
        repository.save(resolved);

        merger.merge("n1", List.of(thread("旧线索", "再次被分析提及", null)), 6);

        PlotThread after = repository.findById("t1").orElseThrow();
        assertThat(after.status()).isEqualTo(PlotThreadStatus.RESOLVED);
        assertThat(after.lastSeenChapter()).isEqualTo(6); // 元数据刷新，状态不变
    }

    @Test
    void blankSummaryDoesNotClobberExistingSummary() {
        merger.merge("n1", List.of(thread("线索A", "有意义的摘要", 1)), 4);

        merger.merge("n1", List.of(thread("线索A", "", 2)), 5);

        PlotThread after = repository.findByTitle("n1", PlotThread.normalized("线索A")).orElseThrow();
        assertThat(after.summary()).isEqualTo("有意义的摘要");
    }

    @Test
    void blankTitleAndNullListAreSkipped() {
        List<PlotThread> saved = merger.merge("n1",
                List.of(thread("", "无标题", 1), thread(null, "空标题", 1)), 4);

        assertThat(saved).isEmpty();
        assertThat(repository.findByNovelId("n1")).isEmpty();
    }

    @Test
    void relatedCharactersAreUnionedAcrossMerges() {
        merger.merge("n1", List.of(thread("线索B", "s", 1)), 3);
        merger.merge("n1", List.of(new EndingAnalysis.EndingThread("线索B", "s2", "r", 2,
                List.of("赵怜云"))), 5);

        PlotThread after = repository.findByTitle("n1", PlotThread.normalized("线索B")).orElseThrow();
        assertThat(after.relatedCharacters()).containsExactly("林默", "赵怜云");
    }
}
