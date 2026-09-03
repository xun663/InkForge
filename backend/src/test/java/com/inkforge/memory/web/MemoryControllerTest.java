package com.inkforge.memory.web;

import com.inkforge.chapter.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Zero-key end-to-end: upload → explicit extraction (Mock provider) → memory overview →
 * continuation SSE still works alongside Story Memory.
 */
@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class MemoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullMemoryFlowWithMockProvider() throws Exception {
        // 1. upload
        String location = mockMvc.perform(multipart("/api/novels")
                        .file(new MockMultipartFile("file", "utf8_standard.txt", "text/plain",
                                Fixtures.bytes("utf8_standard.txt"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String novelId = new tools.jackson.databind.ObjectMapper()
                .readTree(location).path("id").asText();

        // 2. explicit extraction of the last 3 chapters (default window)
        mockMvc.perform(post("/api/novels/{id}/memory/extract", novelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].stats.quotesValidated").isNumber())
                .andExpect(jsonPath("$[2].chapterOrdinal").value(5));

        // 3. extraction is idempotent for processed chapters
        mockMvc.perform(post("/api/novels/{id}/memory/extract", novelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 4. memory overview: characters with current facts, events, summaries, stats
        mockMvc.perform(get("/api/novels/{id}/memory", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastExtractedOrdinal").value(5))
                .andExpect(jsonPath("$.characters.length()").value(2))
                .andExpect(jsonPath("$.characters[0].currentFacts.length()").isNumber())
                .andExpect(jsonPath("$.recentEvents.length()").value(3))
                .andExpect(jsonPath("$.aggregateStats.chaptersExtracted").value(3))
                .andExpect(jsonPath("$.aggregateStats.characters").value(2));

        // 5. continuation still works alongside memory (memory-aware context path)
        var result = mockMvc.perform(post("/api/novels/{id}/continuations", novelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:token").contains("event:done");
    }

    @Test
    void extractionOnUnknownNovelReturns404() throws Exception {
        mockMvc.perform(post("/api/novels/no-such/memory/extract")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(get("/api/novels/no-such/memory"))
                .andExpect(status().isNotFound());
    }
}
