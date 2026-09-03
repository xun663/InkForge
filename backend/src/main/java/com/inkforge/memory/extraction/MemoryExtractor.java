package com.inkforge.memory.extraction;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.MemoryExtractionStats;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.LlmUsage;
import com.inkforge.provider.TaskType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs ONE structured extraction (summary + characters + facts + events) for a chapter.
 *
 * <p>Adaptive extraction (§19 of the design):
 * normal web-novel chapters (token count within the input budget after reserving the
 * prompt overhead and output space) are extracted IN FULL in one call; oversized chapters
 * fall back to chunked extraction with a deterministic merge. LLM output goes through
 * strict JSON parsing, structural validation and a bounded retry loop.
 */
@Component
public class MemoryExtractor {

    private static final String SYSTEM_TEMPLATE = "memory.extraction.txt";
    private static final String USER_TEMPLATE = "memory.extraction.user.txt";
    private static final String REPAIR_TEMPLATE = "memory.repair.txt";

    private final LlmProvider llmProvider;
    private final PromptCatalog promptCatalog;
    private final TokenCounter tokenCounter;
    private final ExtractionValidator validator;
    private final MemoryExtractionProperties properties;
    private final ObjectMapper objectMapper;

    public MemoryExtractor(LlmProvider llmProvider, PromptCatalog promptCatalog,
                           TokenCounter tokenCounter, ExtractionValidator validator,
                           MemoryExtractionProperties properties, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.promptCatalog = promptCatalog;
        this.tokenCounter = tokenCounter;
        this.validator = validator;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * @param chapterNoText display text for the chapter (第N章 / title for special chapters)
     */
    public ExtractionOutcome extract(Chapter chapter, String chapterNoText) {
        long start = System.nanoTime();
        List<String> inputs = selectInputs(chapter);
        List<ChapterExtractionResult> results = new ArrayList<>();
        int retries = 0;
        int quotesValidated = 0;
        int quotesRejected = 0;
        LlmUsage totalUsage = new LlmUsage(0, 0);
        String lastError = null;

        for (String input : inputs) {
            ChapterExtractionResult parsed = null;
            String systemPrompt = promptCatalog.render(SYSTEM_TEMPLATE, Map.of(
                    "chapterNo", chapterNoText,
                    "chapterTitle", chapter.title(),
                    "schema", ExtractionSchema.text()));
            String userPrompt = promptCatalog.render(USER_TEMPLATE, Map.of("content", input));

            for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
                try {
                    LlmResponse response = llmProvider.complete(new LlmRequest(
                            List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt)),
                            properties.extractionMaxOutputTokens(),
                            properties.extractionTemperature(),
                            llmProvider.defaultModel(),
                            TaskType.MEMORY_EXTRACTION));
                    totalUsage = add(totalUsage, response.usage());
                    parsed = parse(response.content());
                    // structural validation — throws and triggers a repair retry
                    validator.validate(parsed, chapter.content(), properties.sourceQuoteMaxChars());
                    break;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    if (attempt < properties.maxRetries()) {
                        retries++;
                        systemPrompt = promptCatalog.render(REPAIR_TEMPLATE, Map.of(
                                "error", lastError == null ? "未知解析错误" : lastError));
                    }
                }
            }
            if (parsed == null) {
                return new ExtractionOutcome(null, new MemoryExtractionStats(
                        0, 0, 0, quotesValidated, quotesRejected, retries,
                        elapsedMs(start), totalUsage),
                        "提取失败（重试 " + retries + " 次后仍无法解析）：" + lastError);
            }
            ExtractionValidator.ValidationResult validation =
                    validator.validate(parsed, chapter.content(), properties.sourceQuoteMaxChars());
            quotesValidated += validation.quotesValidated();
            quotesRejected += validation.quotesRejected();
            results.add(validation.cleaned());
        }

        ChapterExtractionResult merged = merge(results);
        int facts = merged.characters().stream().mapToInt(c -> c.facts().size()).sum();
        MemoryExtractionStats stats = new MemoryExtractionStats(
                merged.characters().size(), facts, merged.events().size(),
                quotesValidated, quotesRejected, retries, elapsedMs(start), totalUsage);
        return new ExtractionOutcome(merged, stats, null);
    }

    /** Adaptive input selection: full text when it fits the input budget, chunked otherwise. */
    private List<String> selectInputs(Chapter chapter) {
        int fixedTokens = countFixedOverhead(chapter) + properties.extractionReservedOutputTokens();
        int available = properties.extractionInputBudget() - fixedTokens;
        if (available <= 0) {
            throw new IllegalArgumentException(
                    "提取输入预算过小：提示词与预留输出已占用 " + fixedTokens
                            + " tokens（预算 " + properties.extractionInputBudget() + "）");
        }
        String content = chapter.content();
        if (tokenCounter.count(content) <= available) {
            return List.of(content); // 情况 A：普通章节全文一次提取
        }
        return chunk(content, available); // 情况 B/C：超长 fallback
    }

    private int countFixedOverhead(Chapter chapter) {
        String system = promptCatalog.render(SYSTEM_TEMPLATE, Map.of(
                "chapterNo", "", "chapterTitle", chapter.title(), "schema", ExtractionSchema.text()));
        String userSkeleton = promptCatalog.render(USER_TEMPLATE, Map.of("content", ""));
        return tokenCounter.count(system) + tokenCounter.count(userSkeleton);
    }

    /** Simple deterministic chunking: accumulate sentences until the token budget is hit, with overlap. */
    private List<String> chunk(String content, int maxTokens) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = content.split("(?<=[。！？…\\n])");
        StringBuilder current = new StringBuilder();
        String previous = "";
        for (String sentence : sentences) {
            if (tokenCounter.count(current + sentence) > maxTokens && !current.isEmpty()) {
                chunks.add(current.toString());
                int overlap = Math.min(properties.chunkOverlapChars(), current.length());
                previous = current.substring(current.length() - overlap);
                current = new StringBuilder(previous);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }

    /** Deterministic merge of per-chunk extractions: facts dedupe naturally downstream via fact keys. */
    private ChapterExtractionResult merge(List<ChapterExtractionResult> results) {
        if (results.size() == 1) {
            return results.getFirst();
        }
        List<ExtractedCharacter> characters = new ArrayList<>();
        List<ExtractedEvent> events = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<String> keyEvents = new ArrayList<>();
        List<ExtractedSummaryCharacter> summaryCharacters = new ArrayList<>();
        List<String> locations = new ArrayList<>();
        List<String> items = new ArrayList<>();
        List<String> threads = new ArrayList<>();
        for (ChapterExtractionResult result : results) {
            summaries.add(result.summary().summary());
            keyEvents.addAll(result.summary().keyEvents());
            summaryCharacters.addAll(result.summary().characters());
            locations.addAll(result.summary().locations());
            items.addAll(result.summary().importantItems());
            threads.addAll(result.summary().unresolvedThreads());
            characters.addAll(result.characters());
            events.addAll(result.events());
        }
        ExtractedSummary summary = new ExtractedSummary(
                String.join("；", summaries), keyEvents, summaryCharacters,
                locations, items, threads);
        return new ChapterExtractionResult(summary, characters, events);
    }

    private ChapterExtractionResult parse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 返回为空");
        }
        String json = raw.trim();
        json = json.replaceAll("(?s)^```json\\s*", "").replaceAll("(?s)\\s*```$", "");
        int firstBrace = json.indexOf('{');
        int lastBrace = json.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw new IllegalArgumentException("LLM 输出中未找到 JSON 对象");
        }
        json = json.substring(firstBrace, lastBrace + 1);
        return objectMapper.readValue(json, ChapterExtractionResult.class);
    }

    private static LlmUsage add(LlmUsage a, LlmUsage b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new LlmUsage(a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Extraction outcome: result is null on final failure (caller records FAILED, pipeline continues). */
    public record ExtractionOutcome(ChapterExtractionResult result,
                                    MemoryExtractionStats stats,
                                    String errorMessage) {
    }
}
