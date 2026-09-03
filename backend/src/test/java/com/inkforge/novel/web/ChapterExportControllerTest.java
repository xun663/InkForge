package com.inkforge.novel.web;

import com.inkforge.chapter.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class ChapterExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsSelectedChaptersAsUtf8Txt() throws Exception {
        String novelId = uploadFixture();

        mockMvc.perform(get("/api/novels/{id}/chapters/{ordinal}", novelId, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordinal").value(2))
                .andExpect(jsonPath("$.title").value("拜入山门"))
                .andExpect(jsonPath("$.content").isNotEmpty());

        mockMvc.perform(post("/api/novels/{id}/chapters/export", novelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordinals\":[2,5]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("拜入山门")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("番外 山门旧事")));
    }

    @Test
    void emptyOrdinalsIs400UnknownIs404() throws Exception {
        String novelId = uploadFixture();

        mockMvc.perform(post("/api/novels/{id}/chapters/export", novelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordinals\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/novels/{id}/chapters/export", novelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordinals\":[99]}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/novels/{id}/chapters/{ordinal}", novelId, 99))
                .andExpect(status().isNotFound());
    }

    private String uploadFixture() throws Exception {
        String body = mockMvc.perform(multipart("/api/novels")
                        .file(new MockMultipartFile("file", "utf8_standard.txt", "text/plain",
                                Fixtures.bytes("utf8_standard.txt"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).path("id").asText();
    }
}
