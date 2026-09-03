package com.inkforge.planning.web;

import com.inkforge.chapter.Chapter;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.planning.PlotThreadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6：规划 API 零 key 端到端（MockLlmProvider 的 PLANNING 罐头输出）。
 * options→plan→confirm→abandon 全链路 + 错误码契约（ApiError.error 稳定机器码）。
 * 计划注入生成的部分在 ContinuationPlanInjectionTest / PlanningMemoryIsolationTest 覆盖。
 */
@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class PlanningControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private PlotThreadRepository plotThreadRepository;

    private String novelId;

    @BeforeEach
    void seedNovel() {
        novelId = "novel-plan-" + System.nanoTime();
        novelRepository.save(new Novel(novelId, "规划测试小说", "plan.txt", List.of(
                new Chapter(0, 1, "第一章", "林默初入宗门。"),
                new Chapter(1, 2, "第二章", "血魔夜袭，林默受伤。"))));
    }

    @Test
    void plotChoiceOptionsReturnDirectionArrayWithoutPersistingPlans() throws Exception {
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/options")
                        .contentType("application/json")
                        .content("{\"mode\":\"PLOT_CHOICE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].title").isNotEmpty())
                .andExpect(jsonPath("$[0].summary").isNotEmpty())
                .andExpect(jsonPath("$[0].directionGoal").isNotEmpty());

        // 候选方向是临时数据：不产生计划
        mockMvc.perform(get("/api/novels/" + novelId + "/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(plotThreadRepository.findByNovelId(novelId)).isEmpty();
    }

    @Test
    void endingOptionsCreateDraftPlanWithAnalysisAndUpsertThreads() throws Exception {
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/options")
                        .contentType("application/json")
                        .content("{\"mode\":\"ENDING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.mode").value("ENDING"))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.analysis.mainArc").isNotEmpty())
                .andExpect(jsonPath("$.analysis.threads.length()").value(2));

        // 完结分析 upsert 了 PlotThread（规划层）
        assertThat(plotThreadRepository.findByNovelId(novelId)).hasSize(2);
    }

    @Test
    void fullDirectionFlowCreateConfirmAbandon() throws Exception {
        String body = mockMvc.perform(post("/api/novels/" + novelId + "/continuations/plan")
                        .contentType("application/json")
                        .content("""
                                {"mode":"PLOT_CHOICE","direction":{"title":"调查失踪案",
                                "summary":"调查连续失踪案件。","directionGoal":"揭开真相"},
                                "userInstruction":"节奏快一点"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.userInstruction").value("节奏快一点"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String planId = new tools.jackson.databind.ObjectMapper()
                .readTree(body).get("planId").asString();

        mockMvc.perform(post("/api/novels/" + novelId + "/plans/" + planId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(post("/api/novels/" + novelId + "/plans/" + planId + "/abandon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABANDONED"));
    }

    @Test
    void unknownNovelIs404WithMachineCode() throws Exception {
        mockMvc.perform(post("/api/novels/missing/continuations/options")
                        .contentType("application/json")
                        .content("{\"mode\":\"PLOT_CHOICE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void unknownModeIs400WithMachineCode() throws Exception {
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/options")
                        .contentType("application/json")
                        .content("{\"mode\":\"WHATEVER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void missingBodyIs400() throws Exception {
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/options"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void directionWithoutTitleIs400() throws Exception {
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/plan")
                        .contentType("application/json")
                        .content("{\"mode\":\"PLOT_CHOICE\",\"direction\":{\"summary\":\"无标题\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void unknownPlanIs404() throws Exception {
        mockMvc.perform(get("/api/novels/" + novelId + "/plans/no-such-plan"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void endingAnalysisUpsertsThreadsDeterministicallyAcrossRegeneration() throws Exception {
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/options")
                        .contentType("application/json")
                        .content("{\"mode\":\"ENDING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/novels/" + novelId + "/continuations/options")
                        .contentType("application/json")
                        .content("{\"mode\":\"ENDING\"}"))
                .andExpect(status().isOk());

        // 两次分析：草稿被替换（旧草稿 ABANDONED），线索仍只有 2 条（upsert）
        assertThat(plotThreadRepository.findByNovelId(novelId)).hasSize(2);
        assertThat(novelRepository.findById(novelId)).isPresent();
    }
}
