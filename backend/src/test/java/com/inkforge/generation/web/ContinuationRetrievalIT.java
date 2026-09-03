package com.inkforge.generation.web;

import com.inkforge.chapter.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3-E end-to-end (zero key): upload → extract (projection) → continue → done event
 * carries retrievedCount + retrievalTraceId → trace API resolves it. Also: no memory →
 * continuation still succeeds with retrievedCount=0.
 */
@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class ContinuationRetrievalIT {

    private static final Pattern DONE_DATA = Pattern.compile("event:done\\ndata:(.+)", Pattern.MULTILINE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String upload() throws Exception {
        String body = mockMvc.perform(multipart("/api/novels")
                        .file(new MockMultipartFile("file", "utf8_standard.txt", "text/plain",
                                Fixtures.bytes("utf8_standard.txt"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("id").asText();
    }

    private JsonNode continueAndGetDone(String novelId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/novels/{id}/continuations", novelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = DONE_DATA.matcher(body);
        assertThat(matcher.find()).isTrue();
        return objectMapper.readTree(matcher.group(1));
    }

    @Test
    void doneEventCarriesRetrievalMetadataAndTraceIsResolvable() throws Exception {
        String novelId = upload();
        // build story memory → chunks exist → retrieval runs
        mockMvc.perform(post("/api/novels/{id}/memory/extract", novelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        JsonNode done = continueAndGetDone(novelId);

        assertThat(done.path("retrievedCount").asInt()).isPositive();
        String traceId = done.path("retrievalTraceId").asText();
        assertThat(traceId).isNotBlank();

        // trace API resolves, scoped to the novel
        mockMvc.perform(get("/api/novels/{id}/retrieval-traces/{traceId}", novelId, traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generationId").value(done.path("generationId").asText()))
                .andExpect(jsonPath("$.pipeline.bm25").isArray())
                .andExpect(jsonPath("$.pipeline.final").isArray())
                .andExpect(jsonPath("$.queries.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        // novel-scope isolation: wrong novel → 404
        mockMvc.perform(get("/api/novels/other-novel/retrieval-traces/{traceId}", traceId))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutMemoryContinuationStillSucceedsWithZeroRetrieval() throws Exception {
        String novelId = upload(); // no extraction → no memory → no retrieval

        JsonNode done = continueAndGetDone(novelId);

        assertThat(done.path("retrievedCount").asInt()).isZero();
        assertThat(done.path("retrievalTraceId").isNull()).isTrue();
        assertThat(done.path("generationId").asText()).isNotBlank(); // 续写成功
    }

    @Test
    void traceListEndpointWorks() throws Exception {
        String novelId = upload();
        mockMvc.perform(post("/api/novels/{id}/memory/extract", novelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        continueAndGetDone(novelId);

        mockMvc.perform(get("/api/novels/{id}/retrieval-traces", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/novels/{id}/retrieval-traces", novelId).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
