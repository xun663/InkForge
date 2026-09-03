package com.inkforge.planning;

import java.util.List;

/**
 * ENDING 模式的完结分析（规划层数据，非 Canon）：主线、人物弧、伏笔、
 * 世界状态、可舍弃支线、最终冲突、结局方向、未解决线索。
 * 只存在于 mode=ENDING 的 StoryPlan.analysis 中，不写入 Story Memory。
 */
public record EndingAnalysis(
        String mainArc,
        List<CharacterArc> characterArcs,
        List<String> foreshadowing,
        String worldState,
        List<String> droppableSubplots,
        String finalConflict,
        String endingDirection,
        List<EndingThread> threads) {

    public EndingAnalysis {
        if (characterArcs == null) {
            characterArcs = List.of();
        }
        if (foreshadowing == null) {
            foreshadowing = List.of();
        }
        if (droppableSubplots == null) {
            droppableSubplots = List.of();
        }
        if (threads == null) {
            threads = List.of();
        }
    }

    /** 人物弧：名称 + 未完成的弧线描述。 */
    public record CharacterArc(String name, String arc) {
        public CharacterArc {
            if (name == null) {
                name = "";
            }
            if (arc == null) {
                arc = "";
            }
        }
    }

    /**
     * 未解决线索（规划层提炼）。resolution 表示该计划打算如何收束——
     * 这只是计划意图，不代表剧情已经解决（PlotThread 仍保持 OPEN）。
     */
    public record EndingThread(String title, String summary, String resolution,
                               Integer firstSeenChapter, List<String> relatedCharacters) {
        public EndingThread {
            if (title == null) {
                title = "";
            }
            if (summary == null) {
                summary = "";
            }
            if (resolution == null) {
                resolution = "";
            }
            if (relatedCharacters == null) {
                relatedCharacters = List.of();
            }
        }
    }
}
