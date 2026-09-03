package com.inkforge.retrieval;

import com.inkforge.common.LlmException;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.TaskType;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM listwise re-ranker (OPTIONAL — selected via {@code inkforge.retrieval.reranker=llm}).
 *
 * <p>Protocol: numbered candidates (max {@code rerankMaxCandidates}, each truncated to
 * {@code rerankCandidateMaxChars}) go into the prompt; the LLM returns a JSON array of
 * candidate NUMBERS; numbers are mapped back to the ORIGINAL RetrievalResults (text
 * never modified). Any protocol violation throws {@link RerankException} — it is NOT
 * swallowed here; HybridRetrievalService degrades to the fusion ranking.
 */
public class LlmListwiseReranker implements Reranker {

    private static final String SYSTEM_TEMPLATE = "rerank.system.txt";
    private static final String USER_TEMPLATE = "rerank.user.txt";

    private final LlmProvider llmProvider;
    private final PromptCatalog promptCatalog;
    private final RetrievalProperties properties;
    private final ObjectMapper objectMapper;

    public LlmListwiseReranker(LlmProvider llmProvider, PromptCatalog promptCatalog,
                               RetrievalProperties properties, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.promptCatalog = promptCatalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<RetrievalResult> input = candidates.stream()
                .limit(properties.rerankMaxCandidates())
                .toList();

        String system = promptCatalog.render(SYSTEM_TEMPLATE, Map.of());
        String user = promptCatalog.render(USER_TEMPLATE, Map.of(
                "query", query,
                "candidates", renderCandidates(input)));

        LlmResponse response;
        try {
            response = llmProvider.complete(new LlmRequest(
                    List.of(ChatMessage.system(system), ChatMessage.user(user)),
                    512, 0.0, llmProvider.defaultModel(), TaskType.RERANK));
        } catch (LlmException e) {
            // Rerank 是可选增强：任何 LLM 失败（网络/空 content）都转 RerankException，
            // 由 HybridRetrievalService 回退 fusion 排名，绝不阻断检索。
            throw new RerankException("Rerank 调用失败: " + e.getMessage());
        }

        int[] numbers = parseNumbers(response.content());
        List<RetrievalResult> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int number : numbers) {
            if (number < 1 || number > input.size()) {
                throw new RerankException("非法编号: " + number);
            }
            if (!seen.add(number)) {
                throw new RerankException("重复编号: " + number);
            }
            ordered.add(input.get(number - 1)); // 原对象引用 — text 不变
        }
        if (ordered.isEmpty()) {
            throw new RerankException("协议不完整：空编号列表");
        }
        return ordered.stream().limit(topK).toList();
    }

    private String renderCandidates(List<RetrievalResult> input) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
                    .append(truncate(input.get(i).text(), properties.rerankCandidateMaxChars()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    /** Strict JSON array parsing; any deviation → RerankException. */
    private int[] parseNumbers(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RerankException("Rerank 输出为空");
        }
        String json = raw.trim();
        json = json.replaceAll("(?s)^```json\\s*", "").replaceAll("(?s)\\s*```$", "");
        int firstBracket = json.indexOf('[');
        int lastBracket = json.lastIndexOf(']');
        if (firstBracket < 0 || lastBracket <= firstBracket) {
            throw new RerankException("Rerank 输出中未找到编号数组: " + raw);
        }
        json = json.substring(firstBracket, lastBracket + 1);
        try {
            return objectMapper.readValue(json, int[].class);
        } catch (Exception e) {
            throw new RerankException("Rerank 输出解析失败: " + raw, e);
        }
    }
}
