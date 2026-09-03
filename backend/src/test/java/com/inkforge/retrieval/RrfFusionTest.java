package com.inkforge.retrieval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF math is verified with hand-computable values: with k=60,
 * rank 1 → 1/61 ≈ 0.0163934, rank 2 → 1/62 ≈ 0.0161290, …
 */
class RrfFusionTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private static RetrievalResult result(String chunkId, double score) {
        return new RetrievalResult(chunkId, "n1", 1, MemoryChunkType.EVENT, "src:" + chunkId,
                "文本-" + chunkId, score);
    }

    @Test
    void singleRankingKeepsOrderAndScores() {
        List<RetrievalResult> out = RrfFusion.fuse(
                List.of(List.of(result("a", 5), result("b", 3))), 60, 10);

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b");
        assertThat(out.getFirst().score()).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(out.get(1).score()).isCloseTo(1.0 / 62, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void twoRankingsAccumulateScores() {
        // a: rank1 in both → 2/61 ≈ 0.0327869; b: rank2 in first only → 1/62 ≈ 0.0161290
        List<RetrievalResult> out = RrfFusion.fuse(List.of(
                List.of(result("a", 1), result("b", 1)),
                List.of(result("a", 1), result("c", 1))), 60, 10);

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b", "c");
        assertThat(out.getFirst().score()).isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(out.get(1).score()).isCloseTo(1.0 / 62, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(out.get(2).score()).isCloseTo(1.0 / 62, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void duplicateChunkIdsMergeKeepingFirstOriginalResult() {
        // 同一 chunk 在两路都出现：分数累加、保留第一路的原始 text
        List<RetrievalResult> out = RrfFusion.fuse(List.of(
                List.of(result("a", 9), result("b", 8)),
                List.of(result("b", 7), result("a", 6))), 60, 10);

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b");
        // a: 第一路 rank1 (1/61) + 第二路 rank2 (1/62)
        assertThat(out.getFirst().score()).isCloseTo(1.0 / 61 + 1.0 / 62, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(out.getFirst().text()).isEqualTo("文本-a"); // 第一路原始信息保留
        assertThat(out.get(1).text()).isEqualTo("文本-b");
    }

    @Test
    void rankStartsAtOneAndSkipsNullsWithoutConsumingRanks() {
        List<RetrievalResult> out = RrfFusion.fuse(
                List.of(Arrays.asList(null, result("a", 1))), 60, 10);

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a");
        assertThat(out.getFirst().score()).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void emptyInputsReturnEmpty() {
        assertThat(RrfFusion.fuse(List.of(), 60, 10)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(List.of()), 60, 10)).isEmpty();
        assertThat(RrfFusion.fuse(null, 60, 10)).isEmpty();
    }

    @Test
    void topKIsRespected() {
        List<RetrievalResult> out = RrfFusion.fuse(
                List.of(List.of(result("a", 1), result("b", 1), result("c", 1))), 60, 2);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b");
    }

    @Test
    void differentKChangesScores() {
        double scoreK10 = RrfFusion.fuse(List.of(List.of(result("a", 1))), 10, 1).getFirst().score();
        double scoreK60 = RrfFusion.fuse(List.of(List.of(result("a", 1))), 60, 1).getFirst().score();

        assertThat(scoreK10).isCloseTo(1.0 / 11, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scoreK60).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void threeRankingsMerge() {
        List<RetrievalResult> out = RrfFusion.fuse(List.of(
                List.of(result("a", 1)),
                List.of(result("b", 1)),
                List.of(result("a", 1))), 60, 10);

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b");
        assertThat(out.getFirst().score()).isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
    }
}
