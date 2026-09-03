package com.inkforge.planning;

import java.time.Instant;
import java.util.List;

/**
 * 剧情计划（规划层产物）：三个续写模式统一收敛到它，再由用户确认进入正式生成。
 *
 * <p>边界：StoryPlan 不是 Canon。它的任何内容都不会写入 Story Memory；
 * 只有按计划生成、且被用户保存为正式 Chapter 的文本，未来才可能进入 Memory Extraction。
 */
public record StoryPlan(
        String planId,
        String novelId,
        ContinuationMode mode,
        String title,
        String summary,
        String goal,
        String expectedArc,
        List<PlanStep> steps,
        List<String> relatedCharacters,
        List<String> relatedThreads,
        List<String> relatedEvents,
        String userInstruction,
        EndingAnalysis analysis,
        PlanStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public StoryPlan {
        if (steps == null) {
            steps = List.of();
        }
        if (relatedCharacters == null) {
            relatedCharacters = List.of();
        }
        if (relatedThreads == null) {
            relatedThreads = List.of();
        }
        if (relatedEvents == null) {
            relatedEvents = List.of();
        }
    }

    public StoryPlan withStatus(PlanStatus newStatus, Instant at) {
        return new StoryPlan(planId, novelId, mode, title, summary, goal, expectedArc,
                steps, relatedCharacters, relatedThreads, relatedEvents,
                userInstruction, analysis, newStatus, createdAt, at);
    }

    public StoryPlan withSteps(List<PlanStep> newSteps, Instant at) {
        return new StoryPlan(planId, novelId, mode, title, summary, goal, expectedArc,
                newSteps, relatedCharacters, relatedThreads, relatedEvents,
                userInstruction, analysis, status, createdAt, at);
    }
}
