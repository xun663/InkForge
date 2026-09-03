package com.inkforge.memory.web;

import com.inkforge.memory.FactCategory;
import com.inkforge.memory.extraction.ChapterExtractionResult;
import com.inkforge.memory.extraction.ExtractedCharacter;
import com.inkforge.memory.extraction.ExtractedEvent;
import com.inkforge.memory.extraction.ExtractedFact;
import com.inkforge.memory.extraction.ExtractedSummary;
import com.inkforge.memory.extraction.ExtractedSummaryCharacter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0",
        "inkforge.embedding.provider=mock"})
@AutoConfigureMockMvc
class OutcomeReplayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingPathsReturn400() throws Exception {
        mockMvc.perform(post("/api/import/gzr-outcomes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void replaysTinyFixtureAndListsNovel(@TempDir Path dir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path source = dir.resolve("gzr.txt");
        Files.writeString(source, """
                第一节：开窍
                方源睁开眼睛，确认自己回到了五百年前。
                """);
        Path outcomes = dir.resolve("outcomes");
        Files.createDirectories(outcomes);
        ChapterExtractionResult extraction = new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of("事件"),
                        List.of(new ExtractedSummaryCharacter("方源", "主角")),
                        List.of("古月山寨"), List.of("春秋蝉"), List.of("线索")),
                List.of(new ExtractedCharacter("方源", List.of(), List.of(
                        new ExtractedFact(FactCategory.IDENTITY, "身份", "少年", null, 0.9,
                                "方源睁开眼睛，确认自己回到了五百年前。")))),
                List.of(new ExtractedEvent("重生", "回到五百年前", List.of("方源"),
                        "古月山寨", List.of(), 5,
                        "方源睁开眼睛，确认自己回到了五百年前。")));
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ok", true);
        rec.put("attempts", 1);
        rec.put("inTokens", 10);
        rec.put("outTokens", 5);
        rec.put("ms", 20);
        rec.put("result", mapper.writeValueAsString(extraction));
        Files.writeString(outcomes.resolve("chapter-0.json"), mapper.writeValueAsString(rec));

        String body = """
                {"sourceTxt":"%s","outcomesDir":"%s","title":"蛊真人","embed":true}
                """.formatted(
                source.toAbsolutePath().toString().replace("\\", "\\\\"),
                dir.toAbsolutePath().toString().replace("\\", "\\\\"));

        String created = mockMvc.perform(post("/api/import/gzr-outcomes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("蛊真人"))
                .andExpect(jsonPath("$.chapterCount").value(1))
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String novelId = mapper.readTree(created).path("id").asText();

        mockMvc.perform(get("/api/novels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')].chapterCount".formatted(novelId)).value(1));

        mockMvc.perform(get("/api/novels/{id}/memory", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateStats.chaptersExtracted").value(1))
                .andExpect(jsonPath("$.characters[0].name").value("方源"));
    }
}
