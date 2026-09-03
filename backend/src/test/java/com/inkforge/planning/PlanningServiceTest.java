package com.inkforge.planning;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.LlmException;
import com.inkforge.common.NotFoundException;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.context.BreakpointAnalyzer;
import com.inkforge.context.ContextProperties;
import com.inkforge.generation.CostCalculator;
import com.inkforge.generation.CostProperties;
import com.inkforge.generation.GenerationLog;
import com.inkforge.generation.InMemoryGenerationLogRepository;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.novel.InMemoryNovelRepository;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.LlmUsage;
import com.inkforge.provider.TaskType;
import com.inkforge.retrieval.HybridRetrievalService;
import com.inkforge.retrieval.QueryConstructionService;
import com.inkforge.retrieval.QueryIntentClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6：PlanningService 编排 —— 三模式产出、解析重试、状态机、单活跃计划约束、
 * 规划只写规划层数据（PlotThread/StoryPlan），Story Memory 全程零写入。
 */
class PlanningServiceTest {

    private static final String NOVEL_ID = "n1";
    private static final String DIRECTIONS_JSON = """
            [
              {"title":"方向一","summary":"摘要一","rationale":"理由","involvedCharacters":["林默"],
               "relatedThreads":[],"relatedWorldElements":[],"possibleConflict":"冲突一","newConflict":"",
               "directionGoal":"目标一"},
              {"title":"方向二","summary":"摘要二","rationale":"理由","directionGoal":"目标二"},
              {"title":"方向三","summary":"摘要三","rationale":"理由","directionGoal":"目标三"}
            ]
            """;
    private static final String ENDING_JSON = """
            {
              "mainArc":"魔门战争决战","characterArcs":[{"name":"林默","arc":"成长弧"}],
              "foreshadowing":["剑穗来历"],"worldState":"大战在即",
              "droppableSubplots":["采药支线"],"finalConflict":"林默对血魔","endingDirection":"终结血魔",
              "threads":[{"title":"血魔行踪成谜","summary":"败退后去向不明","resolution":"决战揭露",
                          "firstSeenChapter":1,"relatedCharacters":["血魔"]}],
              "steps":[
                {"index":3,"title":"揭示剑穗","summary":"回溯恩怨","phaseGoal":"收束伏笔"},
                {"index":1,"title":"最终决战","summary":"决战","phaseGoal":"主线收束"}
              ]
            }
            """;

    private final InMemoryNovelRepository novelRepository = new InMemoryNovelRepository();
    private final InMemoryStoryPlanRepository planRepository = new InMemoryStoryPlanRepository();
    private final InMemoryPlotThreadRepository threadRepository = new InMemoryPlotThreadRepository();
    private final InMemoryStoryMemoryRepository memoryRepository = new InMemoryStoryMemoryRepository();
    private final InMemoryGenerationLogRepository logRepository = new InMemoryGenerationLogRepository();
    private final LlmProvider llmProvider = mock(LlmProvider.class);

    private PlanningService service;

    @BeforeEach
    void setUp() {
        // 3 章小说（ordinal 0..2）
        novelRepository.save(new Novel(NOVEL_ID, "测试小说", "test.txt", List.of(
                new Chapter(0, 1, "第一章", "林默初入宗门。"),
                new Chapter(1, 2, "第二章", "血魔夜袭。"),
                new Chapter(2, 3, "第三章", "林默右手受伤，血魔逃离。"))));
        when(llmProvider.name()).thenReturn("mock");
        when(llmProvider.defaultModel()).thenReturn("mock-model");
        service = newService(new PlanningProperties(2048, 0.3, 2, 512, 3));
    }

    private PlanningService newService(PlanningProperties properties) {
        StoryMemoryRepository memory = memoryRepository;
        PlanningContextAssembler assembler = new PlanningContextAssembler(
                memory,
                threadRepository,
                new BreakpointAnalyzer(new ContextProperties(8192, 2000, Map.of())),
                new QueryConstructionService(new QueryIntentClassifier()),
                mockedRetrieval());
        return new PlanningService(novelRepository, planRepository, threadRepository, assembler,
                new PlotThreadMerger(threadRepository),
                new PlanOutputParser(new ObjectMapper()),
                llmProvider, new ClasspathPromptCatalog(), properties, logRepository,
                new CostCalculator(new CostProperties(Map.of())));
    }

    private HybridRetrievalService mockedRetrieval() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveMulti(anyString(), anyList())).thenReturn(List.of());
        return retrieval;
    }

    private void llmReturns(String content) {
        when(llmProvider.complete(any()))
                .thenReturn(new LlmResponse(content, new LlmUsage(100, 50)));
    }

    @Test
    void plotChoiceProposesDirectionsWithoutPersistingAnything() {
        llmReturns(DIRECTIONS_JSON);

        List<PlanDirection> directions = service.proposeDirections(NOVEL_ID, ContinuationMode.PLOT_CHOICE, null);

        assertThat(directions).hasSize(3);
        assertThat(directions.getFirst().title()).isEqualTo("方向一");
        assertThat(directions.getFirst().conflict()).isEqualTo("冲突一");
        // 候选方向是临时数据：计划与线索仓储都为空
        assertThat(planRepository.findByNovelId(NOVEL_ID)).isEmpty();
        assertThat(threadRepository.findByNovelId(NOVEL_ID)).isEmpty();

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmProvider, atLeastOnce()).complete(captor.capture());
        LlmRequest request = captor.getValue();
        assertThat(request.taskType()).isEqualTo(TaskType.PLANNING);
        assertThat(request.temperature()).isEqualTo(0.3);
        assertThat(request.messages().get(1).content()).contains("【规划模式：剧情选择】");
        // 规划日志：type=PLANNING、mode 记录、方向类调用无 planId
        List<GenerationLog> logs = logRepository.findByNovelId(NOVEL_ID);
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().type()).isEqualTo("PLANNING");
        assertThat(logs.getFirst().mode()).isEqualTo("PLOT_CHOICE");
        assertThat(logs.getFirst().planId()).isNull();
        assertThat(logs.getFirst().status()).isEqualTo("SUCCESS");
    }

    @Test
    void expansionUsesExpansionMarkerAndCapsDirectionCount() {
        llmReturns(DIRECTIONS_JSON);
        PlanningService capped = newService(new PlanningProperties(2048, 0.3, 2, 512, 2));

        List<PlanDirection> directions = capped.proposeDirections(NOVEL_ID, ContinuationMode.EXPANSION, "想看新地图");

        assertThat(directions).hasSize(2);
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmProvider, atLeastOnce()).complete(captor.capture());
        assertThat(captor.getValue().messages().get(1).content()).contains("【规划模式：拓展】");
        assertThat(captor.getValue().messages().get(1).content()).contains("想看新地图");
    }

    @Test
    void endingCreatesDraftPlanAndUpsertsPlotThreads() {
        llmReturns(ENDING_JSON);

        StoryPlan plan = service.createEndingPlan(NOVEL_ID, "尽快收束");

        assertThat(plan.status()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.mode()).isEqualTo(ContinuationMode.ENDING);
        assertThat(plan.title()).isEqualTo("终结血魔");
        assertThat(plan.analysis()).isNotNull();
        assertThat(plan.analysis().mainArc()).isEqualTo("魔门战争决战");
        // LLM 编号不可信：重排为 0..n-1
        assertThat(plan.steps()).extracting(PlanStep::index).containsExactly(0, 1);
        assertThat(plan.relatedThreads()).containsExactly("血魔行踪成谜");
        assertThat(plan.userInstruction()).isEqualTo("尽快收束");

        // PlotThread（规划层数据）已 upsert：OPEN、章节钳制到小说范围
        List<PlotThread> threads = threadRepository.findOpenByNovelId(NOVEL_ID);
        assertThat(threads).hasSize(1);
        assertThat(threads.getFirst().firstSeenChapter()).isEqualTo(1);
        assertThat(threads.getFirst().lastSeenChapter()).isEqualTo(2);
        assertThat(threads.getFirst().relatedCharacters()).containsExactly("血魔");

        List<GenerationLog> logs = logRepository.findByNovelId(NOVEL_ID);
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().type()).isEqualTo("PLANNING");
        assertThat(logs.getFirst().planId()).isEqualTo(plan.planId());
    }

    @Test
    void endingRegenerationReplacesDraft() {
        llmReturns(ENDING_JSON);
        StoryPlan first = service.createEndingPlan(NOVEL_ID, null);
        StoryPlan second = service.createEndingPlan(NOVEL_ID, null);

        assertThat(first.planId()).isNotEqualTo(second.planId());
        assertThat(planRepository.findById(first.planId()).orElseThrow().status())
                .isEqualTo(PlanStatus.ABANDONED);
        assertThat(planRepository.findByNovelId(NOVEL_ID)).hasSize(2);
        // upsert：同一线索只有一条
        assertThat(threadRepository.findByNovelId(NOVEL_ID)).hasSize(1);
    }

    @Test
    void confirmedPlanBlocksNewPlanCreation() {
        llmReturns(ENDING_JSON);
        StoryPlan plan = service.createEndingPlan(NOVEL_ID, null);
        service.confirm(NOVEL_ID, plan.planId());

        assertThatThrownBy(() -> service.createEndingPlan(NOVEL_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请先放弃当前计划");
    }

    @Test
    void garbageOutputRetriesThenThrowsLlmException() {
        when(llmProvider.complete(any()))
                .thenReturn(new LlmResponse("抱歉，我无法完成该请求。", new LlmUsage(10, 5)));

        assertThatThrownBy(() -> service.proposeDirections(NOVEL_ID, ContinuationMode.PLOT_CHOICE, null))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("重试 2 次");
        verify(llmProvider, times(3)).complete(any()); // 1 + maxRetries
        assertThat(logRepository.findByNovelId(NOVEL_ID)).hasSize(1);
        assertThat(logRepository.findByNovelId(NOVEL_ID).getFirst().status()).isEqualTo("FAILED");
    }

    @Test
    void garbageThenValidSucceeds() {
        when(llmProvider.complete(any()))
                .thenReturn(new LlmResponse("垃圾输出", new LlmUsage(10, 5)))
                .thenReturn(new LlmResponse(DIRECTIONS_JSON, new LlmUsage(100, 50)));

        List<PlanDirection> directions = service.proposeDirections(NOVEL_ID, ContinuationMode.PLOT_CHOICE, null);

        assertThat(directions).hasSize(3);
        verify(llmProvider, times(2)).complete(any());
        assertThat(logRepository.findByNovelId(NOVEL_ID)).hasSize(1);
        assertThat(logRepository.findByNovelId(NOVEL_ID).getFirst().status()).isEqualTo("SUCCESS");
    }

    @Test
    void planLifecycleTransitionsAndGuards() {
        StoryPlan plan = service.createPlanFromDirection(NOVEL_ID, ContinuationMode.PLOT_CHOICE,
                new PlanDirection("方向一", "摘要一", "理由", List.of(), List.of(), List.of(),
                        "冲突", "", "目标"), null);

        assertThat(plan.status()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().getFirst().summary()).contains("预期冲突：冲突");

        StoryPlan confirmed = service.confirm(NOVEL_ID, plan.planId());
        assertThat(confirmed.status()).isEqualTo(PlanStatus.CONFIRMED);

        StoryPlan completed = service.complete(NOVEL_ID, plan.planId());
        assertThat(completed.status()).isEqualTo(PlanStatus.COMPLETED);
        // 终态后不再允许任何推进
        assertThatThrownBy(() -> service.confirm(NOVEL_ID, plan.planId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.abandon(NOVEL_ID, plan.planId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.complete(NOVEL_ID, plan.planId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planOfAnotherNovelIsNotFound() {
        StoryPlan plan = service.createPlanFromDirection(NOVEL_ID, ContinuationMode.PLOT_CHOICE,
                new PlanDirection("方向一", "摘要", "", List.of(), List.of(), List.of(), "", "", ""), null);

        assertThatThrownBy(() -> service.get("other-novel", plan.planId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.confirm("other-novel", plan.planId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unknownNovelYieldsNotFound() {
        assertThatThrownBy(() -> service.proposeDirections("missing", ContinuationMode.PLOT_CHOICE, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.createEndingPlan("missing", null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void storyMemoryIsNeverTouchedByPlanningFlows() {
        llmReturns(ENDING_JSON);
        service.createEndingPlan(NOVEL_ID, null);
        llmReturns(DIRECTIONS_JSON);
        service.proposeDirections(NOVEL_ID, ContinuationMode.PLOT_CHOICE, null);

        // 规划只写规划层数据；Story Memory（人物/事实/事件/摘要）保持全空
        assertThat(memoryRepository.findCharacters(NOVEL_ID)).isEmpty();
        assertThat(memoryRepository.findEvents(NOVEL_ID, 10, true)).isEmpty();
        assertThat(memoryRepository.findSummaries(NOVEL_ID, 0, 100)).isEmpty();
    }
}
