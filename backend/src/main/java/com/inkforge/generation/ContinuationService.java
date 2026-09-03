package com.inkforge.generation;

import com.inkforge.common.NotFoundException;
import com.inkforge.context.ContinuationContextBuilder;
import com.inkforge.context.ContextProperties;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmUsage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the Phase 1 continuation pipeline:
 * context build (budget) → prompt → LLM stream → GenerationLog.
 * Every run carries a unique generationId used to correlate the SSE done event
 * with its GenerationLog entry.
 */
@Service
public class ContinuationService {

    private final NovelRepository novelRepository;
    private final ContinuationContextBuilder contextBuilder;
    private final ContextProperties contextProperties;
    private final GenerationProperties generationProperties;
    private final LlmProvider llmProvider;
    private final GenerationLogRepository generationLogRepository;
    private final CostCalculator costCalculator;

    public ContinuationService(NovelRepository novelRepository,
                               ContinuationContextBuilder contextBuilder,
                               ContextProperties contextProperties,
                               GenerationProperties generationProperties,
                               LlmProvider llmProvider,
                               GenerationLogRepository generationLogRepository,
                               CostCalculator costCalculator) {
        this.novelRepository = novelRepository;
        this.contextBuilder = contextBuilder;
        this.contextProperties = contextProperties;
        this.generationProperties = generationProperties;
        this.llmProvider = llmProvider;
        this.generationLogRepository = generationLogRepository;
        this.costCalculator = costCalculator;
    }

    /**
     * @throws NotFoundException when the novel does not exist (mapped to HTTP 404)
     */
    public Flux<GenerationEvent> streamContinuation(String novelId, GenerationOptions options) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + novelId));
        String generationId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();

        // P3-E: buildWithTrace exposes retrieval observability (trace id + count);
        // the default implementation (P1 fallback path) returns null/0
        com.inkforge.context.ContextBuildResult buildResult =
                contextBuilder.buildWithTrace(novel, contextProperties.contextMaxTokens(), generationId);
        List<ChatMessage> messages = buildResult.messages();
        LlmRequest llmRequest = new LlmRequest(messages,
                options.maxOutputTokens() != null && options.maxOutputTokens() > 0
                        ? options.maxOutputTokens() : generationProperties.maxOutputTokens(),
                options.temperature() != null ? options.temperature() : generationProperties.temperature(),
                llmProvider.defaultModel());

        return streamAndLog(generationId, novelId, startedAt, llmRequest, buildResult);
    }

    private Flux<GenerationEvent> streamAndLog(String generationId, String novelId,
                                               long startedAt, LlmRequest llmRequest,
                                               com.inkforge.context.ContextBuildResult buildResult) {
        StringBuilder content = new StringBuilder();
        AtomicReference<LlmUsage> usageRef = new AtomicReference<>();

        Flux<GenerationEvent> tokenEvents = llmProvider.stream(llmRequest)
                .handle((event, sink) -> {
                    if (event.delta() != null && !event.delta().isEmpty()) {
                        content.append(event.delta());
                        sink.next(new GenerationEvent.Token(event.delta()));
                    } else if (event.usage() != null) {
                        usageRef.set(event.usage());
                    }
                });

        Mono<GenerationEvent> doneEvent = Mono.defer(() -> {
            LlmUsage usage = usageRef.get() != null ? usageRef.get()
                    : new LlmUsage(0, content.length()); // provider reported no usage
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            GenerationLog log = new GenerationLog(
                    generationId, novelId, llmProvider.name(), llmRequest.model(),
                    usage.promptTokens(), usage.completionTokens(), latencyMs,
                    costCalculator.estimate(llmRequest.model(), usage.promptTokens(), usage.completionTokens()),
                    "SUCCESS", null, "CONTINUATION", Instant.now());
            generationLogRepository.save(log);
            return Mono.just((GenerationEvent) new GenerationEvent.Done(new GenerationEvent.DoneMeta(
                    log.generationId(), log.provider(), log.model(),
                    log.promptTokens(), log.completionTokens(), log.totalTokens(),
                    log.latencyMs(), log.estimatedCostUsd(),
                    buildResult.retrievedCount(), buildResult.retrievalTraceId())));
        });

        return tokenEvents.concatWith(doneEvent)
                .onErrorResume(e -> {
                    long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
                    LlmUsage usage = usageRef.get() != null ? usageRef.get() : new LlmUsage(0, 0);
                    generationLogRepository.save(new GenerationLog(
                            generationId, novelId, llmProvider.name(), llmRequest.model(),
                            usage.promptTokens(), usage.completionTokens(), latencyMs,
                            BigDecimal.ZERO, "FAILED", e.getMessage(), "CONTINUATION", Instant.now()));
                    return Flux.just((GenerationEvent) new GenerationEvent.Error(
                            e.getMessage() == null ? "生成失败" : e.getMessage()));
                });
    }
}
