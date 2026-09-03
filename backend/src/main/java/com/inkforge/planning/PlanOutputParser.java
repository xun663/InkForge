package com.inkforge.planning;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划输出的确定性解析与校验（复用 MemoryExtractor 的两级校验风格）：
 * <ul>
 *   <li>结构性错误（找不到 JSON、方向全为空、完结计划无收束步骤）→ IllegalArgumentException，
 *       由调用方喂给 repair prompt 重试</li>
 *   <li>单条错误（标题/摘要空白、字段类型不对）→ 静默丢弃该条，不影响其余</li>
 * </ul>
 * 支持两种根形态：{@code [...]}（方向数组）与 {@code {"directions":[...]}}（对象包裹）；
 * 完结计划要求对象根。LLM 的步骤编号不可信，解析后统一重排为 0..n-1。
 */
@Component
public class PlanOutputParser {

    private final ObjectMapper objectMapper;

    public PlanOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析 PLOT_CHOICE / EXPANSION 的候选方向数组。至少 1 条有效方向，否则结构性失败。 */
    public List<PlanDirection> parseDirections(String raw) {
        String json = extractJson(raw, "JSON 数组");
        List<PlanDirection> candidates = new ArrayList<>();
        if (json.charAt(0) == '{') {
            // 对象包裹形态：{"directions":[...]}
            DirectionsWrapper wrapper = objectMapper.readValue(json, DirectionsWrapper.class);
            if (wrapper.directions() != null) {
                for (PlanDirection direction : wrapper.directions()) {
                    addIfValid(candidates, direction);
                }
            }
        } else {
            PlanDirection[] array = objectMapper.readValue(json, PlanDirection[].class);
            for (PlanDirection direction : array) {
                addIfValid(candidates, direction);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("规划输出中没有任何有效的剧情方向（title/summary 缺失或为空）");
        }
        return candidates;
    }

    /** 解析 ENDING 的完结计划对象根。无有效收束步骤 → 结构性失败。 */
    public EndingPlanParse parseEndingPlan(String raw) {
        String json = extractJson(raw, "JSON 对象");
        ParsedEndingPlan parsed = objectMapper.readValue(json, ParsedEndingPlan.class);
        List<EndingAnalysis.CharacterArc> arcs = new ArrayList<>();
        if (parsed.characterArcs() != null) {
            for (EndingAnalysis.CharacterArc arc : parsed.characterArcs()) {
                if (arc != null && !arc.name().isBlank()) {
                    arcs.add(arc);
                }
            }
        }
        List<EndingAnalysis.EndingThread> threads = new ArrayList<>();
        if (parsed.threads() != null) {
            for (EndingAnalysis.EndingThread thread : parsed.threads()) {
                if (thread != null && !thread.title().isBlank()) {
                    threads.add(thread);
                }
            }
        }
        List<PlanStep> steps = new ArrayList<>();
        if (parsed.steps() != null) {
            int index = 0;
            for (RawStep step : parsed.steps()) {
                if (step != null && step.title() != null && !step.title().isBlank()) {
                    steps.add(new PlanStep(index++, step.title(), step.summary(), step.phaseGoal()));
                }
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("完结计划缺少有效的收束步骤（steps 为空或全部无效）");
        }
        EndingAnalysis analysis = new EndingAnalysis(parsed.mainArc(), arcs, parsed.foreshadowing(),
                parsed.worldState(), parsed.droppableSubplots(), parsed.finalConflict(),
                parsed.endingDirection(), threads);
        return new EndingPlanParse(analysis, steps);
    }

    private static void addIfValid(List<PlanDirection> candidates, PlanDirection direction) {
        if (direction != null && direction.title() != null && !direction.title().isBlank()
                && direction.summary() != null && !direction.summary().isBlank()) {
            candidates.add(direction);
        }
        // 单条无效：丢弃，不失败
    }

    /** 剥 ```json 围栏 + 截取首个 JSON 结构，与 MemoryExtractor.parse 同款确定性策略。 */
    private static String extractJson(String raw, String expectedShape) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 返回为空");
        }
        String json = raw.trim();
        json = json.replaceAll("(?s)^```json\\s*", "").replaceAll("(?s)\\s*```$", "");
        int firstObject = json.indexOf('{');
        int firstArray = json.indexOf('[');
        int open;
        char close;
        if (firstObject >= 0 && (firstArray < 0 || firstObject < firstArray)) {
            open = firstObject;
            close = '}';
        } else if (firstArray >= 0) {
            open = firstArray;
            close = ']';
        } else {
            throw new IllegalArgumentException("LLM 输出中未找到 JSON" + expectedShape);
        }
        int last = json.lastIndexOf(close);
        if (last <= open) {
            throw new IllegalArgumentException("LLM 输出中未找到完整的 JSON" + expectedShape);
        }
        return json.substring(open, last + 1);
    }

    /** 一次完结计划解析结果：分析块 + 收束步骤（已重排编号）。 */
    public record EndingPlanParse(EndingAnalysis analysis, List<PlanStep> steps) {
    }

    /** Jackson 3 默认忽略未知字段；LLM 输出多余键不会反序列化失败。 */
    private record ParsedEndingPlan(
            String mainArc,
            List<EndingAnalysis.CharacterArc> characterArcs,
            List<String> foreshadowing,
            String worldState,
            List<String> droppableSubplots,
            String finalConflict,
            String endingDirection,
            List<EndingAnalysis.EndingThread> threads,
            List<RawStep> steps) {
    }

    /**
     * JSON 绑定专用的步骤 DTO：index 用 Integer 而非 int。
     * Jackson 3 对 record 缺失的基本类型分量按 null 处理并直接报错
     * （Cannot map null into type int），域 record 的 int index 不能直接暴露给 JSON 绑定。
     */
    private record RawStep(Integer index, String title, String summary, String phaseGoal) {
    }

    private record DirectionsWrapper(List<PlanDirection> directions) {
    }
}
