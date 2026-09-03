package com.inkforge.provider;

import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.config.RuntimeLlmConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime LLM switching: DelegatingLlmProvider routes to mock / OpenAI-compatible per config. */
class DelegatingLlmProviderTest {

    private final TokenCounter tokenCounter = new JtokkitTokenCounter();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeLlmConfig runtimeConfig = new RuntimeLlmConfig();
    private final MockLlmProvider mock = new MockLlmProvider(
            tokenCounter, objectMapper,
            new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0)));
    private final DelegatingLlmProvider provider = new DelegatingLlmProvider(runtimeConfig, mock, objectMapper);

    @Test
    void defaultMockRoutesToMock() {
        runtimeConfig.init("mock", "https://unused", "", "unused", 300);
        assertThat(provider.name()).isEqualTo("mock");
        assertThat(provider.defaultModel()).isEqualTo("inkforge-mock");
    }

    @Test
    void switchedToDeepseekRoutesToOpenAiCompatibleWithRuntimeValues() {
        runtimeConfig.init("mock", "https://unused", "", "unused", 300);
        runtimeConfig.update("deepseek", "https://api.deepseek.com", "deepseek-chat", "sk-test");
        assertThat(provider.name()).isEqualTo("deepseek");
        assertThat(provider.defaultModel()).isEqualTo("deepseek-chat");
    }

    @Test
    void switchedBackToMockRestoresMock() {
        runtimeConfig.init("deepseek", "https://api.deepseek.com", "sk-test", "deepseek-chat", 300);
        runtimeConfig.update("mock", null, null, null);
        assertThat(provider.name()).isEqualTo("mock");
        assertThat(provider.defaultModel()).isEqualTo("inkforge-mock");
    }
}
