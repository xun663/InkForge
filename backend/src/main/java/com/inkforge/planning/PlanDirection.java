package com.inkforge.planning;

import java.util.List;

/**
 * 候选剧情方向（PLOT_CHOICE / EXPANSION 共用）。
 * 临时数据：不持久化、不是 Canon、绝不写入 Story Memory；
 * 用户选定后才通过 PlanningService 转成 StoryPlan。
 */
public record PlanDirection(
        String title,
        String summary,
        String rationale,
        List<String> involvedCharacters,
        List<String> relatedThreads,
        List<String> relatedWorldElements,
        String possibleConflict,
        String newConflict,
        String directionGoal) {

    public PlanDirection {
        if (involvedCharacters == null) {
            involvedCharacters = List.of();
        }
        if (relatedThreads == null) {
            relatedThreads = List.of();
        }
        if (relatedWorldElements == null) {
            relatedWorldElements = List.of();
        }
    }

    /** PLOT_CHOICE 展示 possibleConflict，EXPANSION 展示 newConflict；取非空者。 */
    public String conflict() {
        if (possibleConflict != null && !possibleConflict.isBlank()) {
            return possibleConflict;
        }
        return newConflict == null ? "" : newConflict;
    }
}
