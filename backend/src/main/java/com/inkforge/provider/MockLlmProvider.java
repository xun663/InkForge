package com.inkforge.provider;

import com.inkforge.common.TokenCounter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic offline provider so the full pipeline works without any API key.
 *
 * <p>Two canned behaviors selected by {@link TaskType}:
 * <ul>
 *   <li>CONTINUATION: emits a fixed passage chunk by chunk</li>
 *   <li>MEMORY_EXTRACTION: emits a canned, schema-conformant extraction JSON whose
 *       sourceQuote is derived from the chapter text in the request — so the quote
 *       substring validation always passes regardless of the uploaded novel</li>
 * </ul>
 */
public class MockLlmProvider implements LlmProvider {

    public static final String NAME = "mock";
    public static final String MODEL = "inkforge-mock";

    private static final String MOCK_PASSAGE = """
            林默缓缓睁开双眼，后山的夜风穿过松林，带来一丝若有若无的血腥气。
            他试着活动右臂，手腕处顿时传来一阵钝痛——血魔那一掌留下的伤势，比预想的更深。
            "伤势未愈之前，不可再动玄霜剑。"他在心中默念。
            月光落在山道尽头，一截被折断的剑穗静静躺在碎石之间，那正是昨夜交战时遗落的。
            林默的眼神微微一凝，俯身将其拾起。
            他知道，血魔绝不会善罢甘休。
            """;

    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final long delayMs;

    public MockLlmProvider(TokenCounter tokenCounter, ObjectMapper objectMapper, LlmProperties properties) {
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
        this.delayMs = properties.mock().delayMs();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String defaultModel() {
        return MODEL;
    }

    @Override
    public Flux<ProviderStreamEvent> stream(LlmRequest request) {
        String response = buildResponse(request);
        Flux<ProviderStreamEvent> flux = Flux.fromIterable(buildEvents(request, response));
        if (delayMs > 0) {
            flux = flux.delayElements(Duration.ofMillis(delayMs));
        }
        return flux;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String response = buildResponse(request);
        StringBuilder content = new StringBuilder();
        LlmUsage usage = null;
        for (ProviderStreamEvent event : buildEvents(request, response)) {
            if (event.delta() != null && !event.delta().isEmpty()) {
                content.append(event.delta());
            }
            if (event.usage() != null) {
                usage = event.usage();
            }
        }
        return new LlmResponse(content.toString(), usage);
    }

    private String buildResponse(LlmRequest request) {
        return switch (request.taskType()) {
            case MEMORY_EXTRACTION -> buildExtractionJson(request);
            case RERANK -> buildRerankJson(request);
            case PLANNING -> buildPlanningJson(request);
            case CONTINUATION -> MOCK_PASSAGE;
        };
    }

    private List<ProviderStreamEvent> buildEvents(LlmRequest request, String response) {
        int promptTokens = tokenCounter.count(messagesToText(request));
        List<ProviderStreamEvent> events = new ArrayList<>();
        if (request.taskType() == TaskType.CONTINUATION) {
            for (String chunk : MOCK_PASSAGE.split("(?<=\\n)|(?<=[。！？…])")) {
                if (!chunk.isEmpty()) {
                    events.add(ProviderStreamEvent.delta(chunk));
                }
            }
        } else {
            events.add(ProviderStreamEvent.delta(response)); // 结构化任务：单块完整响应
        }
        events.add(ProviderStreamEvent.usage(
                new LlmUsage(promptTokens, tokenCounter.count(response))));
        return events;
    }

    /**
     * Canned RERANK response: echoes the candidate numbers in their input order
     * (line-leading {@code [N]} entries in the user prompt). Validates the
     * LlmListwiseReranker call chain — NOT real semantic re-ranking.
     */
    private String buildRerankJson(LlmRequest request) {
        String userContent = request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .reduce("", (a, b) -> b);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(?m)^\\[(\\d+)\\]").matcher(userContent);
        List<String> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(matcher.group(1));
        }
        return "[" + String.join(",", numbers) + "]";
    }

    /** Canned extraction JSON; the quote is derived from the chapter text so validation always passes. */
    private String buildExtractionJson(LlmRequest request) {
        String chapterText = request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .reduce("", (a, b) -> b); // last user message carries the chapter content
        String quote = firstSentence(chapterText, 300);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("summary", new LinkedHashMap<>(Map.of(
                "summary", "（Mock 摘要）本章描写了林默与血魔在后山的一场对峙，林默受伤，血魔逃离。",
                "keyEvents", List.of("林默与血魔对峙", "林默右手受伤", "血魔逃离"),
                "characters", List.of(
                        Map.of("name", "林默", "role", "主角"),
                        Map.of("name", "血魔", "role", "反派")),
                "locations", List.of("天剑宗后山"),
                "importantItems", List.of("玄霜剑"),
                "unresolvedThreads", List.of("血魔逃离后的行动未知", "林默右手伤势尚未恢复"))));

        Map<String, Object> linMoState = new LinkedHashMap<>();
        linMoState.put("category", "STATE");
        linMoState.put("attribute", "当前状态");
        linMoState.put("value", "右手受伤");
        linMoState.put("targetCharacter", null);
        linMoState.put("confidence", 0.9);
        linMoState.put("sourceQuote", quote);

        Map<String, Object> linMoRelation = new LinkedHashMap<>();
        linMoRelation.put("category", "RELATIONSHIP");
        linMoRelation.put("attribute", "关系");
        linMoRelation.put("value", "敌对");
        linMoRelation.put("targetCharacter", "血魔");
        linMoRelation.put("confidence", 0.95);
        linMoRelation.put("sourceQuote", quote);

        Map<String, Object> xueMoState = new LinkedHashMap<>();
        xueMoState.put("category", "STATE");
        xueMoState.put("attribute", "当前状态");
        xueMoState.put("value", "逃离");
        xueMoState.put("targetCharacter", null);
        xueMoState.put("confidence", 0.9);
        xueMoState.put("sourceQuote", quote);

        root.put("characters", List.of(
                Map.of("name", "林默", "aliases", List.of(),
                        "facts", List.of(linMoState, linMoRelation)),
                Map.of("name", "血魔", "aliases", List.of(),
                        "facts", List.of(xueMoState))));

        root.put("events", List.of(Map.of(
                "title", "后山对峙",
                "description", "林默与血魔在后山对峙，林默右手受伤，血魔逃离。",
                "participants", List.of("林默", "血魔"),
                "location", "天剑宗后山",
                "consequences", List.of("林默右手受伤", "血魔逃离"),
                "importance", 4,
                "sourceQuote", quote)));

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Mock extraction JSON 序列化失败", e);
        }
    }

    /**
     * Canned PLANNING response：按用户提示词中的【规划模式：…】标记区分三种模式。
     * 完结模式返回单对象（EndingPlan 形状）；剧情选择/拓展返回方向数组。
     * 人物与 MOCK_PASSAGE 世界一致（林默/血魔/天剑宗），标题含空白以验证归一化合并。
     */
    private String buildPlanningJson(LlmRequest request) {
        String userContent = request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .reduce("", (a, b) -> b);
        if (userContent.contains("【规划模式：完结】")) {
            return PLANNING_ENDING_JSON;
        }
        return PLANNING_DIRECTIONS_JSON;
    }

    private static final String PLANNING_DIRECTIONS_JSON = """
            [
              {"title":"调查青云城 失踪事件","summary":"继续当前城市线，调查近期出现的连续失踪案件。",
               "rationale":"与当前章节留下的悬念直接相关。",
               "involvedCharacters":["林默","血魔"],"relatedThreads":["失踪案背后主使"],
               "relatedWorldElements":["青云城"],"possibleConflict":"可能触发与天魔宗的正面冲突。",
               "newConflict":null,"directionGoal":"揭开失踪案真相"},
              {"title":"黑衣人再次现身","summary":"夜袭的黑衣人重新出现，目标直指玄霜剑。",
               "rationale":"延续昨夜交战留下的敌人线索。",
               "involvedCharacters":["林默"],"relatedThreads":[],"relatedWorldElements":[],
               "possibleConflict":null,"newConflict":"黑衣人身份成谜，敌友难辨。","directionGoal":"查明黑衣人来历"},
              {"title":"开启青云秘境","summary":"后山遗迹异动，秘境入口显现。",
               "rationale":"世界空间尚未开发，适合拓展新篇。",
               "involvedCharacters":["林默"],"relatedThreads":[],"relatedWorldElements":["青云秘境","上古遗迹"],
               "possibleConflict":null,"newConflict":"秘境中的势力与传承之争。","directionGoal":"开启新地图与新机缘"}
            ]
            """;

    private static final String PLANNING_ENDING_JSON = """
            {
              "mainArc":"魔门战争进入决战阶段",
              "characterArcs":[{"name":"林默","arc":"从受伤弟子成长为独当一面的剑客"}],
              "foreshadowing":["第1章遗落的剑穗来历未明"],
              "worldState":"天剑宗与魔门对峙，大战一触即发",
              "droppableSubplots":["后山采药支线"],
              "finalConflict":"林默与血魔的最终对决",
              "endingDirection":"以林默亲手终结血魔、重铸玄霜剑作结",
              "threads":[
                {"title":"血魔逃离后的 行动未知","summary":"血魔败退后行踪成谜",
                 "resolution":"最终决战中揭露血魔巢穴","firstSeenChapter":1,
                 "relatedCharacters":["血魔"]},
                {"title":"林默右手伤势尚未恢复","summary":"伤势影响战力",
                 "resolution":"决战前痊愈并突破","firstSeenChapter":1,
                 "relatedCharacters":["林默"]}
              ],
              "steps":[
                {"index":1,"title":"揭示剑穗与血魔的渊源","summary":"回溯血魔与玄霜剑的恩怨","phaseGoal":"收束兵器伏笔"},
                {"index":2,"title":"林默伤愈并突破","summary":"闭关疗伤，突破境界","phaseGoal":"完成人物弧"},
                {"index":3,"title":"最终决战","summary":"林默与血魔决战于后山","phaseGoal":"主线收束"},
                {"index":4,"title":"尾声","summary":"大战落幕，新的平静","phaseGoal":"结局"}
              ]
            }
            """;

    /** First sentence-ish substring of the chapter, capped at maxLen chars. */
    private static String firstSentence(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.strip();
        if (t.length() > maxLen) {
            t = t.substring(0, maxLen);
        }
        int end = -1;
        for (char c : new char[]{'。', '！', '？', '\n'}) {
            int i = t.indexOf(c);
            if (i >= 0 && (end < 0 || i < end)) {
                end = i;
            }
        }
        return end >= 0 ? t.substring(0, end + 1) : t;
    }

    private static String messagesToText(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : request.messages()) {
            sb.append(message.content()).append('\n');
        }
        return sb.toString();
    }
}
