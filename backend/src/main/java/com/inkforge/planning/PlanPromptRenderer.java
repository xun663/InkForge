package com.inkforge.planning;

import com.inkforge.common.prompt.PromptCatalog;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * StoryPlan → 生成附录文本。附录由 ContinuationService 追加到末条 user 消息
 * （不改 ContinuationContextBuilder SPI，对两个 builder 通吃）；
 * 其 token 量由调用方计量并从上下文预算中预留。
 */
@Component
public class PlanPromptRenderer {

    private static final String APPENDIX_TEMPLATE = "continuation.generation-with-plan.txt";

    private final PromptCatalog promptCatalog;

    public PlanPromptRenderer(PromptCatalog promptCatalog) {
        this.promptCatalog = promptCatalog;
    }

    /** 渲染生成附录。ENDING + stepIndex 时明确"当前执行阶段"；userInstruction 可空。 */
    public String generationAppendix(StoryPlan plan, Integer stepIndex, String userInstruction) {
        String currentPhase = currentPhase(plan, stepIndex);
        String instruction = userInstruction == null || userInstruction.isBlank()
                ? "" : "\n【用户本次要求】" + userInstruction.trim();
        return promptCatalog.render(APPENDIX_TEMPLATE, Map.of(
                "planBlock", planBlock(plan),
                "currentPhase", currentPhase,
                "userInstruction", instruction));
    }

    /** 计划的确定性文本视图（也用于测试与调试）。 */
    public String planBlock(StoryPlan plan) {
        StringBuilder block = new StringBuilder();
        block.append("模式：").append(plan.mode().label()).append('\n');
        block.append("计划：").append(plan.title()).append('\n');
        if (plan.goal() != null && !plan.goal().isBlank()) {
            block.append("目标：").append(plan.goal()).append('\n');
        }
        for (PlanStep step : plan.steps()) {
            block.append("阶段").append(step.index() + 1).append("：").append(step.title());
            if (step.summary() != null && !step.summary().isBlank()) {
                block.append(" —— ").append(step.summary());
            }
            if (step.phaseGoal() != null && !step.phaseGoal().isBlank()) {
                block.append("（").append(step.phaseGoal()).append("）");
            }
            block.append('\n');
        }
        if (!plan.relatedCharacters().isEmpty()) {
            block.append("相关角色：").append(String.join("、", plan.relatedCharacters())).append('\n');
        }
        if (!plan.relatedThreads().isEmpty()) {
            block.append("关联线索：").append(String.join("；", plan.relatedThreads())).append('\n');
        }
        return block.toString().trim();
    }

    /** ENDING 模式标注当前阶段（stepIndex 钳制到有效范围）；其他模式返回空。 */
    private String currentPhase(StoryPlan plan, Integer stepIndex) {
        if (plan.mode() != ContinuationMode.ENDING || plan.steps().isEmpty()) {
            return "";
        }
        int index = stepIndex == null ? 0 : Math.max(0, Math.min(stepIndex, plan.steps().size() - 1));
        PlanStep step = plan.steps().get(index);
        return "【当前执行阶段】第" + (index + 1) + "/" + plan.steps().size() + " 阶段：" + step.title()
                + (step.phaseGoal() == null || step.phaseGoal().isBlank()
                ? "" : "（" + step.phaseGoal() + "）") + "\n";
    }
}
