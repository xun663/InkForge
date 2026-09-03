package com.inkforge.planning;

import java.time.Instant;
import java.util.List;

/**
 * 剧情线索（规划层持久化数据，非 Canon）：一条尚未完成、正在发展的剧情线/剧情问题。
 * 写入方 = ENDING 规划分析（PlotThreadMerger 确定性 upsert）；OPEN 线索反向供三模式规划使用。
 * 与 Story Memory 严格分离：PlotThread 的存在不改变任何 CharacterFact/StoryEvent。
 */
public record PlotThread(
        String id,
        String novelId,
        String title,
        String summary,
        PlotThreadStatus status,
        Integer firstSeenChapter,
        Integer lastSeenChapter,
        List<String> relatedCharacters,
        Instant createdAt,
        Instant updatedAt) {

    public PlotThread {
        if (relatedCharacters == null) {
            relatedCharacters = List.of();
        }
    }

    /** upsert 匹配键：去除全部空白后的标题。 */
    public static String normalized(String title) {
        return title == null ? "" : title.replaceAll("\\s+", "");
    }

    public PlotThread withStatus(PlotThreadStatus newStatus, Instant at) {
        return new PlotThread(id, novelId, title, summary, newStatus,
                firstSeenChapter, lastSeenChapter, relatedCharacters, createdAt, at);
    }
}
