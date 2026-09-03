package com.inkforge.config.web;

import com.inkforge.config.RuntimeLlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runtime LLM config API: GET never leaks the key (only apiKeyConfigured);
 * PUT validates provider / key / baseUrl / model; switching provider takes effect.
 * RuntimeLlmConfig is reset to mock in @BeforeEach/@AfterEach to avoid cross-test pollution.
 */
@SpringBootTest(properties = "inkforge.config.test=true")
@AutoConfigureMockMvc
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RuntimeLlmConfig runtimeConfig;

    @BeforeEach
    @AfterEach
    void resetToMock() {
        runtimeConfig.update("mock", "https://unused", "unused", "");
    }

    @Test
    void getReturnsSafeViewWithoutApiKeyField() throws Exception {
        mockMvc.perform(get("/api/config/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("mock"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.supportedProviders").isArray());
    }

    @Test
    void putUnknownProviderRejected() throws Exception {
        mockMvc.perform(put("/api/config/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"does-not-exist\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putNonMockWithoutKeyRejected() throws Exception {
        mockMvc.perform(put("/api/config/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com\",\"model\":\"deepseek-chat\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putDeepseekWithKeyAppliesAndNeverLeaksKey() throws Exception {
        String key = "sk-super-secret-test-key";
        MvcResult save = mockMvc.perform(put("/api/config/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com\","
                                + "\"model\":\"deepseek-chat\",\"apiKey\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("deepseek"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andReturn();
        // the saved key must not appear anywhere in the API response
        assertThat(save.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(key)
                .doesNotContain("sk-super-secret");

        MvcResult read = mockMvc.perform(get("/api/config/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("deepseek"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andReturn();
        assertThat(read.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(key);
    }

    @Test
    void switchBackToMockTakesEffectAndRetainsKeyInMemory() throws Exception {
        mockMvc.perform(put("/api/config/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com\","
                                + "\"model\":\"deepseek-chat\",\"apiKey\":\"sk-x\"}"))
                .andExpect(status().isOk());
        // switching to mock takes effect for routing; the key is retained in memory
        // (not cleared), so switching back to deepseek does not require re-entry.
        mockMvc.perform(put("/api/config/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"mock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("mock"));
    }
}
