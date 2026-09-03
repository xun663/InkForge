package com.inkforge.generation;

import com.inkforge.common.NotFoundException;
import com.inkforge.common.TokenCounter;
import com.inkforge.context.ContinuationContextBuilder;
import com.inkforge.context.ContextProperties;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.planning.ContinuationIntent;
import com.inkforge.planning.PlanPromptRenderer;
import com.inkforge.planning.PlanStatus;
import com.inkforge.planning.StoryPlan;
import com.inkforge.planning.StoryPlanRepository;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmUsage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the continuation pipeline:
 * context build (budget) → prompt → LLM stream → GenerationLog.
 * Every run carries a unique generationId used to correlate the SSE done event
 * with its GenerationLog entry.
 *
 * <p>P6 计划注入：legacy 请求（无 mode/planId）行为与 P5 完全一致；携带 planId 时，
 * 已确认的 StoryPlan 被渲染为附录并追加到末条 user 消息，其 token 量从上下文预算中预留
 * （不改 ContinuationContextBuilder SPI，对 MemoryAware/RecentChapters 两个 builder 通吃）。
 * 本类零写 Story Memory；对 StoryPlan 仅做读取与状态推进（CONFIRMED→IN_PROGRESS）。
 */
@Service
public class ContinuationService {

    /** 计划附录的 token 计量留白（避免边界截断）。 */
    private static final int PLAN_RESERVE_MARGIN = 64;

    private final NovelRepository novelRepository;
    private final ContinuationContextBuilder contextBuilder;
    private final ContextProperties contextProperties;
    private final GenerationProperties generationProperties;
    private final LlmProvider llmProvider;
    private final GenerationLogRepository generationLogRepository;
    private final CostCalculator costCalculator;
    private final StoryPlanRepository storyPlanRepository;
    private final TokenCounter tokenCounter;
    private final PlanPromptRenderer planPromptRenderer;

    public ContinuationService(NovelRepository novelRepository,
                               ContinuationContextBuilder contextBuilder,
                               ContextProperties contextProperties,
                               GenerationProperties generationProperties,
                               LlmProvider llmProvider,
                               GenerationLogRepository generationLogRepository,
                               CostCalculator costCalculator,
                               StoryPlanRepository storyPlanRepository,
                               TokenCounter tokenCounter,
                               PlanPromptRenderer planPromptRenderer) {
        this.novelRepository = novelRepository;
        this.contextBuilder = contextBuilder;
        this.contextProperties = contextProperties;
        this.generationProperties = generationProperties;
        this.llmProvider = llmProvider;
        this.generationLogRepository = generationLogRepository;
        this.costCalculator = costCalculator;
        this.storyPlanRepository = storyPlanRepository;
        this.tokenCounter = tokenCounter;
        this.planPromptRenderer = planPromptRenderer;
    }

    /** P6 兼容入口：等价于 legacy 意图（旧调用点零改动）。 */
    public Flux<GenerationEvent> streamContinuation(String novelId, GenerationOptions options) {
        return streamContinuation(novelId, options, ContinuationIntent.legacy());
    }

    /**
     * @throws NotFoundException 小说或计划不存在（HTTP 404）
     * @throws IllegalArgumentException 意图参数不合法 / 计划状态不允许（HTTP 400）
     */
    public Flux<GenerationEvent> streamContinuation(String novelId, GenerationOptions options,
                                                    ContinuationIntent intent) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + novelId));
        String generationId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();

        StoryPlan plan = resolvePlan(novel, intent);
        String appendix = null;
        int effectiveBudget = contextProperties.contextMaxTokens();
        if (plan != null) {
            appendix = planPromptRenderer.generationAppendix(plan, intent.stepIndex(), intent.userInstruction());
            int planTokens = tokenCounter.count(appendix) + PLAN_RESERVE_MARGIN;
            if (planTokens > effectiveBudget / 2) {
                throw new IllegalArgumentException(
                        "剧情计划附录过长（约 " + planTokens + " tokens），超过上下文预算的一半，请精简计划内容");
            }
            effectiveBudget = effectiveBudget - planTokens;
        }

        // P3-E: buildWithTrace exposes retrieval observability (trace id + count);
        // the default implementation (P1 fallback path) returns null/0
        com.inkforge.context.ContextBuildResult buildResult =
                contextBuilder.buildWithTrace(novel, effectiveBudget, generationId);
        List<ChatMessage> messages = plan == null
                ? buildResult.messages()
                : appendPlan(buildResult.messages(), appendix);
        LlmRequest llmRequest = new LlmRequest(messages,
                options.maxOutputTokens() != null && options.maxOutputTokens() > 0
                        ? options.maxOutputTokens() : generationProperties.maxOutputTokens(),
                options.temperature() != null ? options.temperature() : generationProperties.temperature(),
                llmProvider.defaultModel());

        String mode = plan != null ? plan.mode().name()
                : intent.mode() == null ? null : intent.mode().name();
        return streamAndLog(generationId, novelId, startedAt, llmRequest, buildResult, mode,
                plan == null ? null : plan.planId());
    }

    /**
     * 解析规划意图。legacy → null；否则校验计划归属/模式/状态，
     * 并在首次生成时把 CONFIRMED 推进为 IN_PROGRESS。
     */
    private StoryPlan resolvePlan(Novel novel, ContinuationIntent intent) {
        if (intent.isLegacy()) {
            if (intent.mode() != null) {
                throw new IllegalArgumentException("请先确认剧情计划（planId）后再按模式生成");
            }
            if (intent.userInstruction() != null && !intent.userInstruction().isBlank()) {
                throw new IllegalArgumentException("用户要求需与剧情计划（planId）一起使用");
            }
            if (intent.stepIndex() != null) {
                throw new IllegalArgumentException("stepIndex 需要与剧情计划（planId）一起使用");
            }
            return null;
        }
        StoryPlan plan = storyPlanRepository.findById(intent.planId())
                .orElseThrow(() -> new NotFoundException("剧情计划不存在: " + intent.planId()));
        if (!plan.novelId().equals(novel.id())) {
            throw new IllegalArgumentException("剧情计划属于其他小说");
        }
        if (intent.mode() != null && intent.mode() != plan.mode()) {
            throw new IllegalArgumentException("续写模式与计划不符（计划模式为 " + plan.mode() + "）");
        }
        if (plan.status() != PlanStatus.CONFIRMED && plan.status() != PlanStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("剧情计划尚未确认（当前状态 " + plan.status() + "），请先确认方案");
        }
        if (plan.mode() == com.inkforge.planning.ContinuationMode.ENDING && intent.stepIndex() != null) {
            if (intent.stepIndex() < 0 || intent.stepIndex() >= plan.steps().size()) {
                throw new IllegalArgumentException("stepIndex 超出计划阶段范围（0.." + (plan.steps().size() - 1) + "）");
            }
        }
        if (plan.status() == PlanStatus.CONFIRMED) {
            storyPlanRepository.save(plan.withStatus(PlanStatus.IN_PROGRESS, Instant.now()));
        }
        return plan;
    }

    /** 把计划附录并入末条 user 消息（原地改写，不新增消息——两个 builder 的消息结构都保持不变）。 */
    private List<ChatMessage> appendPlan(List<ChatMessage> messages, String appendix) {
        List<ChatMessage> merged = new ArrayList<>(messages);
        for (int i = merged.size() - 1; i >= 0; i--) {
            if ("user".equals(merged.get(i).role())) {
                merged.set(i, ChatMessage.user(merged.get(i).content() + "\n\n" + appendix));
                return List.copyOf(merged);
            }
        }
        return List.copyOf(merged); // 无 user 消息的退化情形（理论上不会发生）
    }

    private Flux<GenerationEvent> streamAndLog(String generationId, String novelId,
                                               long startedAt, LlmRequest llmRequest,
                                               com.inkforge.context.ContextBuildResult buildResult,
                                               String mode, String planId) {
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
                    "SUCCESS", null, "CONTINUATION", mode, planId, Instant.now());
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
                            BigDecimal.ZERO, "FAILED", e.getMessage(), "CONTINUATION", mode, planId,
                            Instant.now()));
                    return Flux.just((GenerationEvent) new GenerationEvent.Error(
                            e.getMessage() == null ? "生成失败" : e.getMessage()));
                });
    }
}
