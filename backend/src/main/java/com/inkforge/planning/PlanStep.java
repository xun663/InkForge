package com.inkforge.planning;

/**
 * 计划中的一个阶段步骤。
 * PLOT_CHOICE/EXPANSION 通常只有 1 步（选定方向）；ENDING 是分阶段收束的多步序列。
 * index 由解析器确定性重排（0 起），不信任 LLM 给出的编号。
 */
public record PlanStep(int index, String title, String summary, String phaseGoal) {

    public PlanStep {
        if (title == null) {
            title = "";
        }
        if (summary == null) {
            summary = "";
        }
        if (phaseGoal == null) {
            phaseGoal = "";
        }
    }
}
