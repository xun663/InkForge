package com.inkforge.provider;

import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockLlmProviderTest {

    private final LlmProvider provider = new MockLlmProvider(
            new JtokkitTokenCounter(), new tools.jackson.databind.ObjectMapper(),
            new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0)));

    private final LlmRequest request = new LlmRequest(
            List.of(ChatMessage.system("system prompt"), ChatMessage.user("user prompt")),
            2048, 0.8, null);

    @Test
    void identifiesItself() {
        assertThat(provider.name()).isEqualTo("mock");
        assertThat(provider.defaultModel()).isEqualTo("inkforge-mock");
    }

    @Test
    void streamEmitsDeltasThenFinalUsage() {
        List<ProviderStreamEvent> events = provider.stream(request).collectList().block();

        assertThat(events).isNotNull().isNotEmpty();
        StringBuilder joined = new StringBuilder();
        LlmUsage usage = null;
        for (ProviderStreamEvent event : events) {
            if (event.delta() != null && !event.delta().isEmpty()) {
                joined.append(event.delta());
            }
            if (event.usage() != null) {
                usage = event.usage();
            }
        }
        assertThat(joined.toString()).contains("玄霜剑").contains("血魔");
        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isPositive();
        assertThat(usage.completionTokens()).isPositive();
        // usage is the last event
        assertThat(events.get(events.size() - 1).usage()).isNotNull();
    }

    @Test
    void completeReturnsFullPassageAndUsage() {
        LlmResponse response = provider.complete(request);

        assertThat(response.content()).contains("玄霜剑");
        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().completionTokens()).isPositive();
    }
}
