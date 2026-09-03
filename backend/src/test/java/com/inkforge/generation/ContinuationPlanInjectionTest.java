package com.inkforge.generation;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.NotFoundException;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.context.ContinuationContextBuilder;
import com.inkforge.context.ContextBuildResult;
import com.inkforge.context.ContextProperties;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.planning.ContinuationIntent;
import com.inkforge.planning.ContinuationMode;
import com.inkforge.planning.InMemoryStoryPlanRepository;
import com.inkforge.planning.PlanStatus;
import com.inkforge.planning.PlanStep;
import com.inkforge.planning.StoryPlan;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmUsage;
import com.inkforge.provider.ProviderStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6：计划注入生成 —— 附录并入末条 user 消息、预算预留扣减、
 * legacy 路径字节级不变（即使存在已确认计划）、计划状态校验与状态推进。
 */
class ContinuationPlanInjectionTest {

    private static final int CONTEXT_MAX = 8192;

    private final NovelRepository novelRepository = mock(NovelRepository.class);
    private final ContinuationContextBuilder contextBuilder = mock(ContinuationContextBuilder.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final InMemoryStoryPlanRepository planRepository = new InMemoryStoryPlanRepository();
    private final InMemoryGenerationLogRepository logRepository = new InMemoryGenerationLogRepository();
    private final TokenCounter tokenCounter = new JtokkitTokenCounter();
    private final com.inkforge.planning.PlanPromptRenderer renderer =
            new com.inkforge.planning.PlanPromptRenderer(new ClasspathPromptCatalog());

    private ContinuationService service;

    private static final Novel NOVEL = new Novel("n1", "测试小说", "t.txt", List.of(
            new Chapter(0, 1, "第一章", "正文。")));

    @BeforeEach
    void setUp() {
        when(novelRepository.findById("n1")).thenReturn(Optional.of(NOVEL));
        when(contextBuilder.buildWithTrace(any(), anyInt(), anyString()))
                .thenReturn(new ContextBuildResult(
                        List.of(ChatMessage.system("sys"), ChatMessage.user("CTX")), "trace-1", 2));
        when(llmProvider.name()).thenReturn("test-provider");
        when(llmProvider.defaultModel()).thenReturn("test-model");
        when(llmProvider.stream(any(LlmRequest.class))).thenReturn(Flux.just(
                ProviderStreamEvent.delta("正文"),
                ProviderStreamEvent.usage(new LlmUsage(10, 2))));
        service = new ContinuationService(novelRepository, contextBuilder,
                new ContextProperties(CONTEXT_MAX, 2000, Map.of()),
                new GenerationProperties(2048, 0.8),
                llmProvider, logRepository,
                new CostCalculator(new CostProperties(Map.of())),
                planRepository, tokenCounter, renderer);
    }

    private StoryPlan confirmedPlan(String planId, ContinuationMode mode) {
        return new StoryPlan(planId, "n1", mode, "调查失踪案", "调查连续失踪案件。", "揭开真相", "",
                List.of(new PlanStep(0, "阶段一", "开始调查", "建立线索"),
                        new PlanStep(1, "阶段二", "找到真凶", "收束方向")),
                List.of("林默"), List.of("失踪案主使"), List.of(),
                null, null, PlanStatus.CONFIRMED, Instant.now(), Instant.now());
    }

    @Test
    void planIsInjectedIntoLastUserMessageWithBudgetReserve() {
        StoryPlan plan = planRepository.save(confirmedPlan("p1", ContinuationMode.PLOT_CHOICE));

        List<GenerationEvent> events = service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(ContinuationMode.PLOT_CHOICE, "p1", null, null)).collectList().block();

        // 预算扣减：8192 - 附录 tokens - 64 留白
        int expectedBudget = CONTEXT_MAX - tokenCounter.count(
                renderer.generationAppendix(plan, null, null)) - 64;
        ArgumentCaptor<Integer> budget = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(contextBuilder).buildWithTrace(any(), budget.capture(), anyString());
        assertThat(budget.getValue()).isEqualTo(expectedBudget);

        // 附录并入末条 user 消息（原 builder 输出仍在）
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(llmProvider).stream(request.capture());
        List<ChatMessage> messages = request.getValue().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("sys");
        assertThat(messages.get(1).content()).startsWith("CTX");
        assertThat(messages.get(1).content()).contains("【本次续写剧情计划（用户已确认）】");
        assertThat(messages.get(1).content()).contains("调查失踪案");

        assertThat(events).hasSize(2);
        GenerationEvent.DoneMeta meta = ((GenerationEvent.Done) events.get(1)).meta();
        assertThat(meta.retrievalTraceId()).isEqualTo("trace-1");

        // 日志带 mode/planId；CONFIRMED → IN_PROGRESS（首次生成即推进）
        GenerationLog saved = logRepository.findByNovelId("n1").getFirst();
        assertThat(saved.mode()).isEqualTo("PLOT_CHOICE");
        assertThat(saved.planId()).isEqualTo("p1");
        assertThat(planRepository.findById("p1").orElseThrow().status()).isEqualTo(PlanStatus.IN_PROGRESS);
    }

    @Test
    void legacyPathIsByteIdenticalEvenWhenConfirmedPlanExists() {
        planRepository.save(confirmedPlan("p1", ContinuationMode.PLOT_CHOICE));

        service.streamContinuation("n1", new GenerationOptions(null, null)).collectList().block();

        // legacy：全额预算、消息 = builder 原样输出、无任何计划痕迹
        ArgumentCaptor<Integer> budget = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(contextBuilder).buildWithTrace(any(), budget.capture(), anyString());
        assertThat(budget.getValue()).isEqualTo(CONTEXT_MAX);

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(llmProvider).stream(request.capture());
        assertThat(request.getValue().messages())
                .containsExactly(ChatMessage.system("sys"), ChatMessage.user("CTX"));

        GenerationLog saved = logRepository.findByNovelId("n1").getFirst();
        assertThat(saved.mode()).isNull();
        assertThat(saved.planId()).isNull();
        // 已确认计划不被 legacy 续写推进
        assertThat(planRepository.findById("p1").orElseThrow().status()).isEqualTo(PlanStatus.CONFIRMED);
    }

    @Test
    void draftPlanIsRejected() {
        StoryPlan plan = planRepository.save(confirmedPlan("p1", ContinuationMode.PLOT_CHOICE));
        planRepository.save(plan.withStatus(PlanStatus.DRAFT, Instant.now()));

        assertThatThrownBy(() -> service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(ContinuationMode.PLOT_CHOICE, "p1", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未确认");
    }

    @Test
    void missingPlanIsNotFound() {
        assertThatThrownBy(() -> service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(ContinuationMode.PLOT_CHOICE, "nope", null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void foreignPlanIsRejected() {
        planRepository.save(new StoryPlan("p2", "other-novel", ContinuationMode.PLOT_CHOICE,
                "别家计划", "s", "g", "", List.of(new PlanStep(0, "t", "s", "g")),
                List.of(), List.of(), List.of(), null, null, PlanStatus.CONFIRMED,
                Instant.now(), Instant.now()));

        assertThatThrownBy(() -> service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(ContinuationMode.PLOT_CHOICE, "p2", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("其他小说");
    }

    @Test
    void modeMismatchIsRejected() {
        planRepository.save(confirmedPlan("p1", ContinuationMode.PLOT_CHOICE));

        assertThatThrownBy(() -> service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(ContinuationMode.EXPANSION, "p1", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模式与计划不符");
    }

    @Test
    void endingStepIndexOutOfRangeIsRejected() {
        planRepository.save(confirmedPlan("p1", ContinuationMode.ENDING));

        assertThatThrownBy(() -> service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(ContinuationMode.ENDING, "p1", 5, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepIndex");
    }

    @Test
    void endingAppendixMarksCurrentPhase() {
        planRepository.save(confirmedPlan("p1", ContinuationMode.ENDING));

        service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(ContinuationMode.ENDING, "p1", 1, "把决战写足")).collectList().block();

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(llmProvider).stream(request.capture());
        String userContent = request.getValue().messages().get(1).content();
        assertThat(userContent).contains("【当前执行阶段】第2/2 阶段：阶段二");
        assertThat(userContent).contains("【用户本次要求】把决战写足");
    }

    @Test
    void userInstructionWithoutPlanIsRejected() {
        assertThatThrownBy(() -> service.streamContinuation("n1", new GenerationOptions(null, null),
                new ContinuationIntent(null, null, null, "只给要求不给计划")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planId");
    }
}
