package com.inkforge.novel.web;

import com.inkforge.chapter.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class NovelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadsUtf8NovelAndListsChapters() throws Exception {
        String location = mockMvc.perform(multipart("/api/novels")
                        .file(new MockMultipartFile("file", "utf8_standard.txt", "text/plain",
                                Fixtures.bytes("utf8_standard.txt"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("utf8_standard"))
                .andExpect(jsonPath("$.chapterCount").value(6))
                .andReturn().getResponse().getContentAsString();
        String novelId = novelIdFrom(location);

        mockMvc.perform(get("/api/novels/{id}", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterCount").value(6));

        mockMvc.perform(get("/api/novels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')].chapterCount".formatted(novelId)).value(6));

        mockMvc.perform(get("/api/novels/{id}/chapters", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].ordinal").value(0))
                .andExpect(jsonPath("$[2].chapterNo").value(1))
                .andExpect(jsonPath("$[2].title").value("拜入山门"))
                .andExpect(jsonPath("$[5].title").value("番外 山门旧事"))
                .andExpect(jsonPath("$[5].chapterNo").doesNotExist());
    }

    @Test
    void uploadsGbkNovelSuccessfully() throws Exception {
        String location = mockMvc.perform(multipart("/api/novels")
                        .file(new MockMultipartFile("file", "gbk_sample.txt", "text/plain",
                                Fixtures.bytes("gbk_sample.txt"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("gbk_sample"))
                .andExpect(jsonPath("$.chapterCount").value(2))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/novels/{id}/chapters/last", novelIdFrom(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("后山遇袭"))
                .andExpect(jsonPath("$.content").isNotEmpty());
    }

    @Test
    void lastChapterAndBreakpointApisWork() throws Exception {
        String location = mockMvc.perform(multipart("/api/novels")
                        .file(new MockMultipartFile("file", "utf8_standard.txt", "text/plain",
                                Fixtures.bytes("utf8_standard.txt"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String novelId = novelIdFrom(location);

        mockMvc.perform(get("/api/novels/{id}/chapters/last", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordinal").value(5))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("断剑")));

        mockMvc.perform(get("/api/novels/{id}/breakpoint", novelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterOrdinal").value(5))
                .andExpect(jsonPath("$.chapterTitle").value("番外 山门旧事"))
                .andExpect(jsonPath("$.tailExcerpt").value(org.hamcrest.Matchers.containsString("断剑")));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/novels")
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void wrongContentTypeOnContinuationReturns415Not500() throws Exception {
        // client errors must never surface as 500
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/novels/no-such-id/continuations")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_media_type"));
    }

    @Test
    void unknownNovelReturns404() throws Exception {
        mockMvc.perform(get("/api/novels/no-such-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(get("/api/novels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/novels/no-such-id/breakpoint"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/novels/no-such-id/continuations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static String novelIdFrom(String body) throws Exception {
        return new tools.jackson.databind.ObjectMapper()
                .readTree(body).path("id").asText();
    }
}
