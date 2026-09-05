package com.inkforge.generation.web;

import com.inkforge.chapter.Chapter;
import com.inkforge.generation.GenerationLog;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class ContinuationControllerTest {

    private static final Pattern DONE_DATA = Pattern.compile("event:done\\r?\\ndata:(.+)", Pattern.MULTILINE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private GenerationLogRepository generationLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String novelId;

    @BeforeEach
    void seedNovel() {
        novelId = "test-novel";
        novelRepository.save(new Novel(novelId, "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "正文。"),
                new Chapter(1, 2, "玄霜剑", "林默握住玄霜剑。"))));
    }

    @Test
    void streamsTokenAndDoneEventsAndRecordsLog() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/novels/{id}/continuations", novelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:token");
        assertThat(body).contains("event:done");
        assertThat(body).contains("玄霜剑"); // mock passage streamed token by token

        String generationId = extractGenerationId(body);
        assertThat(generationId).isNotBlank();

        List<GenerationLog> logs = generationLogRepository.findByNovelId(novelId);
        assertThat(logs).hasSize(1);
        GenerationLog log = logs.getFirst();
        assertThat(log.generationId()).isEqualTo(generationId);
        assertThat(log.provider()).isEqualTo("mock");
        assertThat(log.model()).isEqualTo("inkforge-mock");
        assertThat(log.promptTokens()).isPositive();
        assertThat(log.completionTokens()).isPositive();
        assertThat(log.totalTokens()).isEqualTo(log.promptTokens() + log.completionTokens());
        assertThat(log.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(log.status()).isEqualTo("SUCCESS");

        mockMvc.perform(get("/api/novels/{id}/continuations", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].generationId").value(generationId))
                .andExpect(jsonPath("$[0].provider").value("mock"));
    }

    @Test
    void unknownNovelReturns404BeforeStreaming() throws Exception {
        mockMvc.perform(post("/api/novels/no-such-id/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    private String extractGenerationId(String sseBody) throws Exception {
        Matcher matcher = DONE_DATA.matcher(sseBody);
        assertThat(matcher.find()).as("done event data present").isTrue();
        JsonNode doneData = objectMapper.readTree(matcher.group(1));
        return doneData.path("generationId").asText();
    }
}
