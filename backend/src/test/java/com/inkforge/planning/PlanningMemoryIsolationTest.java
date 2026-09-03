package com.inkforge.planning;

import com.inkforge.chapter.Chapter;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.CharacterStatus;
import com.inkforge.memory.FactCategory;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.LlmUsage;
import com.inkforge.provider.ProviderStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6 核心回归（规格第十八节）：生成与规划绝不修改 Story Memory。
 *
 * <p>种子：CharacterFact「方源/境界=元婴/CURRENT」。测试 LLM 的续写正文断言
 * 「方源突破化神。」（若隔离被破坏，这会成为新事实）、规划输出包含完整的完结计划。
 * 跑完 legacy 续写、ENDING 分析、方向建计划、确认后按计划生成——
 * Memory 必须仍是「元婴=CURRENT」且无任何新增人物/事实/事件/摘要/提取记录。
 */
@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class PlanningMemoryIsolationTest {

    private static final Pattern DONE_DATA = Pattern.compile("event:done\\ndata:(.+)", Pattern.MULTILINE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private StoryMemoryRepository memoryRepository;

    @Autowired
    private PlotThreadRepository plotThreadRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String novelId;
    private String fangyuanId;

    @BeforeEach
    void seedNovelWithMemory() {
        novelId = "iso-" + System.nanoTime();
        novelRepository.save(new Novel(novelId, "隔离测试小说", "iso.txt", List.of(
                new Chapter(0, 1, "第一章", "方源盘膝而坐，体内真元流转。"))));

        fangyuanId = "char-" + novelId;
        Instant now = Instant.now();
        memoryRepository.saveCharacter(new Character(
                fangyuanId, novelId, "方源", List.of(), 0, 0, CharacterStatus.ACTIVE, now, now));
        memoryRepository.saveFact(new CharacterFact(
                "fact-" + novelId, fangyuanId, FactCategory.ABILITY, "境界", "元婴",
                null, FactStatus.CURRENT, 0, null, 0.95, 0, "方源已是元婴修士。", now, now));
    }

    @Test
    void legacyGenerationDoesNotMutateStoryMemory() throws Exception {
        runSse(post("/api/novels/" + novelId + "/continuations")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertMemoryUntouched();
    }

    @Test
    void planningDoesNotMutateStoryMemoryButMayWritePlotThreads() throws Exception {
        // ENDING 分析（upsert PlotThread）+ 方向建计划（替换 DRAFT）
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/options")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ENDING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PLOT_CHOICE","direction":{"title":"闭关突破","summary":"方源闭关冲击化神。",
                                "directionGoal":"完成突破"}}"""))
                .andExpect(status().isOk());

        assertMemoryUntouched();
        // 规划层唯一允许的写入：PlotThread（完结分析提炼的未解决线索）
        assertThat(plotThreadRepository.findByNovelId(novelId)).isNotEmpty();
    }

    @Test
    void generationWithConfirmedPlanDoesNotMutateStoryMemory() throws Exception {
        String body = mockMvc.perform(post("/api/novels/" + novelId + "/continuations/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PLOT_CHOICE","direction":{"title":"闭关突破","summary":"方源闭关冲击化神。",
                                "directionGoal":"完成突破"}}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String planId = objectMapper.readTree(body).get("planId").asString();
        mockMvc.perform(post("/api/novels/" + novelId + "/plans/" + planId + "/confirm"))
                .andExpect(status().isOk());

        runSse(post("/api/novels/" + novelId + "/continuations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"PLOT_CHOICE\",\"planId\":\"" + planId + "\"}"));

        assertMemoryUntouched();
    }

    /** 断言 Memory 与保存前完全一致（规格要求的事实不被续写正文覆盖）。 */
    private void assertMemoryUntouched() {
        List<CharacterFact> currentFacts = memoryRepository.findCurrentFacts(fangyuanId);
        assertThat(currentFacts).hasSize(1);
        assertThat(currentFacts.getFirst().attribute()).isEqualTo("境界");
        assertThat(currentFacts.getFirst().value()).isEqualTo("元婴");
        assertThat(currentFacts.getFirst().status()).isEqualTo(FactStatus.CURRENT);

        assertThat(memoryRepository.findCharacters(novelId)).hasSize(1);
        assertThat(memoryRepository.findEvents(novelId, 10, true)).isEmpty();
        assertThat(memoryRepository.findSummaries(novelId, 0, 100)).isEmpty();
        assertThat(memoryRepository.findExtractionRecords(novelId)).isEmpty();
        // 续写草稿不会被存为章节：小说章节数不变
        assertThat(novelRepository.findById(novelId).orElseThrow().chapterCount()).isEqualTo(1);
    }

    private String runSse(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mockMvc.perform(builder)
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = DONE_DATA.matcher(body);
        assertThat(matcher.find()).as("SSE 应以 done 事件结束：%s", body).isTrue();
        return body;
    }

    /**
     * 隔离测试专用 LLM：正文断言「方源突破化神。」（模拟生成内容越过了 Memory 现状），
     * 规划按模式标记返回罐头 JSON（驱动完整规划链路）。
     */
    @TestConfiguration
    static class IsolationLlmConfig {

        private static final String DIRECTIONS_JSON = """
                [
                  {"title":"闭关突破","summary":"方源闭关冲击化神。","rationale":"衔接正文断言",
                   "involvedCharacters":["方源"],"relatedThreads":[],"relatedWorldElements":[],
                   "possibleConflict":"突破引发心魔。","newConflict":"","directionGoal":"完成突破"}
                ]
                """;

        private static final String ENDING_JSON = """
                {
                  "mainArc":"突破与复仇双线收束","characterArcs":[{"name":"方源","arc":"从元婴走向化神"}],
                  "foreshadowing":[],"worldState":"各方势力蛰伏",
                  "droppableSubplots":[],"finalConflict":"方源与宿敌的终局一战","endingDirection":"以突破化神后的终局战作结",
                  "threads":[{"title":"宿敌伏诛","summary":"宿敌仍在暗中布局","resolution":"终局战伏诛",
                              "firstSeenChapter":1,"relatedCharacters":["方源"]}],
                  "steps":[
                    {"index":1,"title":"闭关冲击化神","summary":"突破境界","phaseGoal":"完成人物弧"},
                    {"index":2,"title":"终局一战","summary":"了结宿命对决","phaseGoal":"主线收束"},
                    {"index":3,"title":"尾声","summary":"尘埃落定","phaseGoal":"结局"}
                  ]
                }
                """;

        @Bean
        @Primary
        LlmProvider isolationLlmProvider() {
            return new LlmProvider() {
                @Override
                public String name() {
                    return "isolation-mock";
                }

                @Override
                public String defaultModel() {
                    return "isolation-model";
                }

                @Override
                public Flux<ProviderStreamEvent> stream(LlmRequest request) {
                    return Flux.just(
                            ProviderStreamEvent.delta("方源突破化神。"),
                            ProviderStreamEvent.usage(new LlmUsage(50, 10)));
                }

                @Override
                public LlmResponse complete(LlmRequest request) {
                    String user = request.messages().stream()
                            .filter(m -> "user".equals(m.role()))
                            .map(ChatMessage::content)
                            .reduce("", (a, b) -> b);
                    String json = user.contains("【规划模式：完结】") ? ENDING_JSON : DIRECTIONS_JSON;
                    return new LlmResponse(json, new LlmUsage(100, 60));
                }
            };
        }
    }
}
