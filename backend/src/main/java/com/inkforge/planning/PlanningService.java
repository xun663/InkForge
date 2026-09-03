package com.inkforge.planning;

import com.inkforge.common.LlmException;
import com.inkforge.common.NotFoundException;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.generation.CostCalculator;
import com.inkforge.generation.GenerationLog;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.LlmUsage;
import com.inkforge.provider.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 剧情规划编排：三种续写模式的规划入口。所有 LLM 输出只变成 StoryPlan / PlanDirection /
 * PlotThread（规划层数据）——本类持有零个 StoryMemoryService/MemoryUpdateService 引用，
 * Planning 全链路绝不写 Story Memory。
 *
 * <p>LLM 调用：阻塞式 complete()，TaskType.PLANNING，低温度；解析失败换 repair prompt
 * 有界重试（复用 MemoryExtractor 模式），最终失败抛 LlmException（→502）。
 *
 * <p>状态机约定（与 MemoryBuildJob 不同，非法转换抛 IllegalArgumentException→400，
 * 因为 GlobalExceptionHandler 不映射 IllegalStateException）：
 * DRAFT → CONFIRMED → IN_PROGRESS → COMPLETED / ABANDONED。
 */
@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    private static final String PLOT_CHOICE_TEMPLATE = "continuation.plot-choice.txt";
    private static final String EXPANSION_TEMPLATE = "continuation.expansion.txt";
    private static final String ENDING_TEMPLATE = "continuation.ending-plan.txt";
    private static final String CONTEXT_TEMPLATE = "planning.context.txt";
    private static final String REPAIR_TEMPLATE = "planning.repair.txt";
    private static final String NO_INSTRUCTION = "（无）";

    private final NovelRepository novelRepository;
    private final StoryPlanRepository storyPlanRepository;
    private final PlotThreadRepository plotThreadRepository;
    private final PlanningContextAssembler contextAssembler;
    private final PlotThreadMerger plotThreadMerger;
    private final PlanOutputParser outputParser;
    private final LlmProvider llmProvider;
    private final PromptCatalog promptCatalog;
    private final PlanningProperties properties;
    private final GenerationLogRepository generationLogRepository;
    private final CostCalculator costCalculator;

    public PlanningService(NovelRepository novelRepository,
                           StoryPlanRepository storyPlanRepository,
                           PlotThreadRepository plotThreadRepository,
                           PlanningContextAssembler contextAssembler,
                           PlotThreadMerger plotThreadMerger,
                           PlanOutputParser outputParser,
                           LlmProvider llmProvider,
                           PromptCatalog promptCatalog,
                           PlanningProperties properties,
                           GenerationLogRepository generationLogRepository,
                           CostCalculator costCalculator) {
        this.novelRepository = novelRepository;
        this.storyPlanRepository = storyPlanRepository;
        this.plotThreadRepository = plotThreadRepository;
        this.contextAssembler = contextAssembler;
        this.plotThreadMerger = plotThreadMerger;
        this.outputParser = outputParser;
        this.llmProvider = llmProvider;
        this.promptCatalog = promptCatalog;
        this.properties = properties;
        this.generationLogRepository = generationLogRepository;
        this.costCalculator = costCalculator;
    }

    /**
     * PLOT_CHOICE / EXPANSION：生成候选剧情方向。方向是临时数据，不持久化。
     */
    public List<PlanDirection> proposeDirections(String novelId, ContinuationMode mode, String userInstruction) {
        if (mode == ContinuationMode.ENDING) {
            throw new IllegalArgumentException("ENDING 模式请使用完结规划接口（POST /continuations/options 携带 mode=ENDING）");
        }
        Novel novel = novelOrThrow(novelId);
        PlanningContextAssembler.PlanningContext context = contextAssembler.assemble(novel, userInstruction);
        List<PlanDirection> parsed = callAndParse(novel, mode, context, userInstruction, null,
                outputParser::parseDirections);
        List<PlanDirection> directions = new ArrayList<>(parsed);
        if (directions.size() > properties.directionCount()) {
            directions = new ArrayList<>(directions.subList(0, properties.directionCount()));
        }
        return List.copyOf(directions);
    }

    /**
     * ENDING：分析故事并生成完结方案（StoryPlan，DRAFT），同时把分析出的未解决线索
     * 确定性 upsert 进 PlotThread。已有 DRAFT 时自动替换（重新生成方案）；
     * 已有 CONFIRMED/IN_PROGRESS 计划时拒绝。
     */
    public StoryPlan createEndingPlan(String novelId, String userInstruction) {
        Novel novel = novelOrThrow(novelId);
        replaceActiveDraft(novelId, "重新生成完结方案前请先放弃当前计划");
        PlanningContextAssembler.PlanningContext context = contextAssembler.assemble(novel, userInstruction);
        String planId = UUID.randomUUID().toString(); // 预生成：GenerationLog 与计划共用同一 id
        PlanOutputParser.EndingPlanParse parse = callAndParse(novel, ContinuationMode.ENDING, context,
                userInstruction, planId, outputParser::parseEndingPlan);

        int lastOrdinal = novel.lastChapter().ordinal();
        List<PlotThread> threads = plotThreadMerger.merge(novelId, parse.analysis().threads(), lastOrdinal);

        Instant now = Instant.now();
        List<String> threadTitles = threads.stream().map(PlotThread::title).toList();
        List<String> arcNames = parse.analysis().characterArcs().stream()
                .map(EndingAnalysis.CharacterArc::name).toList();
        StoryPlan plan = new StoryPlan(
                planId,
                novelId,
                ContinuationMode.ENDING,
                firstNonBlank(parse.analysis().endingDirection(), parse.analysis().mainArc(), "完结方案"),
                parse.analysis().mainArc(),
                parse.analysis().finalConflict(),
                parse.analysis().endingDirection(),
                parse.steps(),
                arcNames,
                threadTitles,
                List.of(),
                userInstruction,
                parse.analysis(),
                PlanStatus.DRAFT,
                now,
                now);
        storyPlanRepository.save(plan);
        return plan;
    }

    /**
     * PLOT_CHOICE / EXPANSION：用户选定方向 → StoryPlan(DRAFT)。
     * 同样受"单活跃计划"约束（已有 DRAFT 自动替换）。
     */
    public StoryPlan createPlanFromDirection(String novelId, ContinuationMode mode,
                                             PlanDirection direction, String userInstruction) {
        if (mode == ContinuationMode.ENDING) {
            throw new IllegalArgumentException("ENDING 模式的计划由完结分析生成，不能由单个方向创建");
        }
        if (direction == null || direction.title() == null || direction.title().isBlank()
                || direction.summary() == null || direction.summary().isBlank()) {
            throw new IllegalArgumentException("所选剧情方向缺少标题或摘要");
        }
        novelOrThrow(novelId);
        replaceActiveDraft(novelId, "开始新方向前请先放弃当前计划");
        Instant now = Instant.now();
        String conflict = direction.conflict();
        String stepSummary = conflict == null || conflict.isBlank()
                ? direction.summary()
                : direction.summary() + "\n预期冲突：" + conflict;
        StoryPlan plan = new StoryPlan(
                UUID.randomUUID().toString(),
                novelId,
                mode,
                direction.title(),
                direction.summary(),
                direction.directionGoal() == null ? "" : direction.directionGoal(),
                "",
                List.of(new PlanStep(0, direction.title(), stepSummary,
                        direction.directionGoal() == null ? "" : direction.directionGoal())),
                direction.involvedCharacters(),
                direction.relatedThreads(),
                List.of(),
                userInstruction,
                null,
                PlanStatus.DRAFT,
                now,
                now);
        storyPlanRepository.save(plan);
        return plan;
    }

    public StoryPlan confirm(String novelId, String planId) {
        return transition(novelId, planId, PlanStatus.CONFIRMED, Set.of(PlanStatus.DRAFT));
    }

    public StoryPlan complete(String novelId, String planId) {
        return transition(novelId, planId, PlanStatus.COMPLETED, Set.of(PlanStatus.CONFIRMED, PlanStatus.IN_PROGRESS));
    }

    public StoryPlan abandon(String novelId, String planId) {
        return transition(novelId, planId, PlanStatus.ABANDONED,
                Set.of(PlanStatus.DRAFT, PlanStatus.CONFIRMED, PlanStatus.IN_PROGRESS));
    }

    public StoryPlan get(String novelId, String planId) {
        StoryPlan plan = storyPlanRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("剧情计划不存在: " + planId));
        if (!plan.novelId().equals(novelId)) {
            throw new NotFoundException("剧情计划不存在: " + planId);
        }
        return plan;
    }

    public List<StoryPlan> list(String novelId) {
        return storyPlanRepository.findByNovelId(novelId);
    }

    private StoryPlan transition(String novelId, String planId, PlanStatus target, Set<PlanStatus> allowedFrom) {
        StoryPlan plan = get(novelId, planId);
        if (!allowedFrom.contains(plan.status())) {
            throw new IllegalArgumentException(
                    "计划状态不允许该操作：当前 " + plan.status() + "，目标 " + target);
        }
        StoryPlan updated = plan.withStatus(target, Instant.now());
        return storyPlanRepository.save(updated);
    }

    /** 单活跃计划约束：存在 DRAFT → 标记 ABANDONED（重新生成=替换草稿）；存在 CONFIRMED/IN_PROGRESS → 拒绝。 */
    private void replaceActiveDraft(String novelId, String blockedMessage) {
        for (StoryPlan existing : storyPlanRepository.findByNovelId(novelId)) {
            if (existing.status() == PlanStatus.DRAFT) {
                storyPlanRepository.save(existing.withStatus(PlanStatus.ABANDONED, Instant.now()));
            } else if (existing.status() == PlanStatus.CONFIRMED || existing.status() == PlanStatus.IN_PROGRESS) {
                throw new IllegalArgumentException(blockedMessage + "（当前计划 " + existing.planId()
                        + " 处于 " + existing.status() + "）");
            }
        }
    }

    /**
     * 阻塞式规划调用 + 有界 repair 重试（解析在循环内：解析失败与调用失败同样触发 repair）；
     * 最终失败抛 LlmException（→502）。每次规划记一条 GenerationLog(type=PLANNING)。
     */
    private <T> T callAndParse(Novel novel, ContinuationMode mode,
                               PlanningContextAssembler.PlanningContext context,
                               String userInstruction, String planId,
                               java.util.function.Function<String, T> parser) {
        String generationId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        String systemPrompt = systemPrompt(mode, novel);
        String userPrompt = userPrompt(mode, context, userInstruction);
        LlmUsage totalUsage = new LlmUsage(0, 0);
        String lastError = null;
        int retries = 0;

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                LlmResponse response = llmProvider.complete(new LlmRequest(
                        List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt)),
                        properties.maxOutputTokens(),
                        properties.temperature(),
                        llmProvider.defaultModel(),
                        TaskType.PLANNING));
                if (response.usage() != null) {
                    totalUsage = response.usage();
                }
                T parsed = parser.apply(response.content()); // 解析失败 = 该次尝试失败
                savePlanningLog(generationId, novel.id(), mode, planId, totalUsage, startedAt, "SUCCESS", null);
                return parsed;
            } catch (Exception e) {
                lastError = e.getMessage();
                if (attempt < properties.maxRetries()) {
                    retries++;
                    systemPrompt = promptCatalog.render(REPAIR_TEMPLATE, Map.of(
                            "error", lastError == null ? "未知解析错误" : lastError));
                }
            }
        }
        log.warn("[planning] 规划失败 novel={} mode={} retries={} error={}", novel.id(), mode, retries, lastError);
        savePlanningLog(generationId, novel.id(), mode, planId, totalUsage, startedAt, "FAILED",
                lastError == null ? "未知错误" : lastError);
        throw new LlmException("剧情规划失败（重试 " + retries + " 次后仍无法解析）："
                + (lastError == null ? "未知错误" : lastError));
    }

    private void savePlanningLog(String generationId, String novelId, ContinuationMode mode,
                                 String planId, LlmUsage usage, long startedAtNanos,
                                 String status, String errorMessage) {
        long latencyMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        LlmUsage safe = usage == null ? new LlmUsage(0, 0) : usage;
        generationLogRepository.save(new GenerationLog(
                generationId, novelId, llmProvider.name(), llmProvider.defaultModel(),
                safe.promptTokens(), safe.completionTokens(), latencyMs,
                costCalculator.estimate(llmProvider.defaultModel(), safe.promptTokens(), safe.completionTokens()),
                status, errorMessage, "PLANNING", mode.name(), planId, Instant.now()));
    }

    private String systemPrompt(ContinuationMode mode, Novel novel) {
        return switch (mode) {
            case PLOT_CHOICE -> promptCatalog.render(PLOT_CHOICE_TEMPLATE, Map.of(
                    "novelTitle", novel.title(),
                    "directionCount", String.valueOf(properties.directionCount())));
            case EXPANSION -> promptCatalog.render(EXPANSION_TEMPLATE, Map.of(
                    "novelTitle", novel.title(),
                    "directionCount", String.valueOf(properties.directionCount())));
            case ENDING -> promptCatalog.render(ENDING_TEMPLATE, Map.of("novelTitle", novel.title()));
        };
    }

    private String userPrompt(ContinuationMode mode, PlanningContextAssembler.PlanningContext context,
                              String userInstruction) {
        String body = promptCatalog.render(CONTEXT_TEMPLATE, Map.of(
                "breakpoint", nullToEmpty(context.breakpoint()),
                "storyState", nullToEmpty(context.storyState()),
                "openThreads", nullToEmpty(context.openThreads()),
                "retrieved", nullToEmpty(context.retrieved()),
                "userInstruction", userInstruction == null || userInstruction.isBlank()
                        ? NO_INSTRUCTION : userInstruction.trim()));
        // 模式标记必须在 user 消息首行（MockLlmProvider 依赖它区分罐头输出）
        return mode.marker() + "\n" + body;
    }

    private Novel novelOrThrow(String novelId) {
        return novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + novelId));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
