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
