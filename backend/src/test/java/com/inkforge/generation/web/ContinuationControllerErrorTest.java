package com.inkforge.generation.web;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.LlmException;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.ProviderStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE error-path test in its own context: a failing LlmProvider replaces the mock
 * (@Primary), verifying the event:error protocol.
 */
@SpringBootTest(properties = {
        "inkforge.llm.provider=mock",
        "inkforge.llm.mock.delay-ms=0"})
@AutoConfigureMockMvc
class ContinuationControllerErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NovelRepository novelRepository;

    @BeforeEach
    void seedNovel() {
        novelRepository.save(new Novel("n-err", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "正文。"))));
    }

    @Test
    void providerFailureEmitsErrorEvent() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/novels/n-err/continuations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:error");
        assertThat(body).contains("模拟流失败");
        assertThat(body).doesNotContain("event:done");
    }

    @TestConfiguration
    static class FailingProviderConfig {

        @Bean
        @Primary
        LlmProvider failingProvider() {
            return new LlmProvider() {
                @Override
                public String name() {
                    return "failing";
                }

                @Override
                public String defaultModel() {
                    return "failing-model";
                }

                @Override
                public Flux<ProviderStreamEvent> stream(LlmRequest request) {
                    return Flux.error(new LlmException("模拟流失败"));
                }

                @Override
                public LlmResponse complete(LlmRequest request) {
                    throw new LlmException("模拟流失败");
                }
            };
        }
    }
}
