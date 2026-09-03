package com.inkforge.benchmark;

import com.inkforge.retrieval.MemoryChunkType;

import java.util.List;

import static com.inkforge.retrieval.MemoryChunkType.EVENT;
import static com.inkforge.retrieval.MemoryChunkType.FACT;
import static com.inkforge.retrieval.MemoryChunkType.SUMMARY;

/**
 * P3-G 人工标注测试集（24 条，基于 fixtures/benchmark_novel.txt）。
 *
 * <p>gold = chapterOrdinal + memoryType（双口径：chunk 命中需两者都匹配；chapter 命中只需章节匹配）。
 * sourceId 为语义描述，用于人工核查（评估以 (chapter, type) 自动匹配）。
 * helpfulness：0 = 无帮助，1 = 有一定帮助，2 = 很有帮助。
 *
 * <p>固定条件：固定小说、固定标注、固定配置（application.yml 默认检索参数）、固定 Mock provider。
 */
public final class BenchmarkQueries {

    private BenchmarkQueries() {
    }

    public record Gold(int chapterOrdinal, MemoryChunkType memoryType, String sourceId) {
    }

    public record BenchmarkQuery(String query, List<Gold> golds, int helpfulness) {
    }

    public static final List<BenchmarkQuery> QUERIES = List.of(
            q("方源与白凝冰初次相遇", 2, g(0, EVENT, "summary:0 青茅山初遇"), g(0, SUMMARY, "summary:0")),
            q("青茅山蛊虫炼制", 2, g(1, EVENT, "event:1 蛊虫认主")),
            q("狐仙福地机缘", 2, g(2, EVENT, "event:2 九叶灵芝")),
            q("方源与白凝冰合作结盟", 2, g(3, EVENT, "event:3 结盟")),
            q("血手魔尊追杀方源如何逃脱", 2, g(4, EVENT, "event:4 矿洞水道逃生")),
            q("方源北原苦修", 1, g(5, SUMMARY, "summary:5 北原半年")),
            q("方源境界五转突破", 2, g(6, SUMMARY, "summary:6 五转"), g(6, FACT, "fact:6 境界五转")),
            q("天机阁情报交易", 1, g(7, EVENT, "event:7 血手魔尊动向")),
            q("方源与白凝冰玄冰洞对峙反目", 2, g(8, EVENT, "event:8 玄冰洞对峙")),
            q("青茅山大战血手魔尊", 2, g(9, EVENT, "event:9 蛊虫大阵")),
            q("方源六转境界", 2, g(10, SUMMARY, "summary:10 六转")),
            q("方源大婚伏杀结局", 1, g(11, EVENT, "event:11 大婚伏杀")),
            q("白凝冰", 1, g(0, SUMMARY, "summary:0"), g(3, EVENT, "event:3"), g(8, EVENT, "event:8")),
            q("血手魔尊", 2, g(4, EVENT, "event:4"), g(9, EVENT, "event:9")),
            q("青茅山", 1, g(0, SUMMARY, "summary:0"), g(1, SUMMARY, "summary:1"), g(9, SUMMARY, "summary:9")),
            q("蛊虫", 2, g(1, EVENT, "event:1"), g(9, EVENT, "event:9")),
            q("三转境界", 1, g(0, FACT, "fact:0 三转")),
            q("五转与六转修炼时间线", 2, g(6, SUMMARY, "summary:6"), g(10, SUMMARY, "summary:10")),
            q("逃脱追杀的方法", 1, g(4, EVENT, "event:4")),
            q("北原与狐仙福地", 1, g(2, EVENT, "event:2"), g(5, SUMMARY, "summary:5")),
            q("白凝冰反目敌对", 2, g(8, EVENT, "event:8")),
            q("天机阁势力情报", 1, g(7, EVENT, "event:7")),
            q("结局婚礼与伏杀", 1, g(11, EVENT, "event:11")),
            q("方源修炼境界历史", 2, g(0, FACT, "fact:0 三转"), g(6, SUMMARY, "summary:6"),
                    g(10, SUMMARY, "summary:10")));

    private static BenchmarkQuery q(String query, int helpfulness, Gold... golds) {
        return new BenchmarkQuery(query, List.of(golds), helpfulness);
    }

    private static Gold g(int chapterOrdinal, MemoryChunkType type, String sourceId) {
        return new Gold(chapterOrdinal, type, sourceId);
    }
}
