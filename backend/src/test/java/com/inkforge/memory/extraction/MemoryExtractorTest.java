package com.inkforge.memory.extraction;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.LlmUsage;
import com.inkforge.provider.MockLlmProvider;
import com.inkforge.provider.ProviderStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryExtractorTest {

    private TokenCounter tokenCounter;
    private PromptCatalog catalog;
    private MemoryExtractionProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        tokenCounter = new JtokkitTokenCounter();
        catalog = new ClasspathPromptCatalog();
        objectMapper = new ObjectMapper();
        properties = new MemoryExtractionProperties(3, 12000, 2048, 2048, 0.2, 2, 0.7, 300, 200);
    }

    private Chapter chapter(String content) {
        return new Chapter(3, 3, "血魔现世", content);
    }

    private MemoryExtractor extractorWith(LlmProvider provider, MemoryExtractionProperties props) {
        return new MemoryExtractor(provider, catalog, tokenCounter,
                new ExtractionValidator(), props, objectMapper);
    }

    @Test
    void mockProviderProducesValidExtractionEndToEnd() {
        // 情况 A：普通章节 → 全文一次提取；mock 的引用取自章节内容，校验必然通过
        MemoryExtractor extractor = extractorWith(new MockLlmProvider(tokenCounter, objectMapper,
                new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0))),
                properties);

        MemoryExtractor.ExtractionOutcome outcome = extractor.extract(chapter(
                "林默与血魔在后山对峙。他试着活动右臂，手腕处顿时传来一阵钝痛。"), "第3章");

        assertThat(outcome.errorMessage()).isNull();
        assertThat(outcome.result()).isNotNull();
        assertThat(outcome.result().characters()).extracting(ExtractedCharacter::name)
                .containsExactlyInAnyOrder("林默", "血魔");
        assertThat(outcome.result().events()).hasSize(1);
        assertThat(outcome.result().summary().unresolvedThreads()).hasSize(2);
        assertThat(outcome.stats().quotesValidated()).isPositive();
        assertThat(outcome.stats().quotesRejected()).isZero();
        assertThat(outcome.stats().tokenUsage()).isNotNull();
    }

    @Test
    void invalidOutputRetriesThenSucceeds() {
        LlmProvider flaky = new LlmProvider() {
            private int calls;

            @Override
            public String name() {
                return "flaky";
            }

            @Override
            public String defaultModel() {
                return "flaky-model";
            }

            @Override
            public Flux<ProviderStreamEvent> stream(LlmRequest request) {
                return Flux.empty();
            }

            @Override
            public LlmResponse complete(LlmRequest request) {
                calls++;
                if (calls <= 2) {
                    return new LlmResponse("这不是 JSON", new LlmUsage(1, 1));
                }
                String quote = firstSentenceOfChapter(request);
                String json = """
                        {"summary": {"summary": "重试后的摘要", "keyEvents": [], "characters": [],
                          "locations": [], "importantItems": [], "unresolvedThreads": []},
                         "characters": [{"name": "林默", "aliases": [],
                          "facts": [{"category": "STATE", "attribute": "当前状态", "value": "受伤",
                                     "targetCharacter": null, "confidence": 0.9, "sourceQuote": "%s"}]}],
                         "events": []}
                        """.formatted(quote);
                return new LlmResponse(json, new LlmUsage(2, 2));
            }
        };

        MemoryExtractor.ExtractionOutcome outcome = extractorWith(flaky, properties).extract(chapter(
                "林默与血魔在后山对峙，右手受伤。"), "第3章");

        assertThat(outcome.errorMessage()).isNull();
        assertThat(outcome.result().characters()).hasSize(1);
        assertThat(outcome.stats().retries()).isEqualTo(2);
    }

    @Test
    void retriesExhaustedYieldsFailedOutcomeNotException() {
        LlmProvider alwaysBad = new LlmProvider() {
            @Override
            public String name() {
                return "bad";
            }

            @Override
            public String defaultModel() {
                return "bad-model";
            }

            @Override
            public Flux<ProviderStreamEvent> stream(LlmRequest request) {
                return Flux.empty();
            }

            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("完全没有 JSON 结构", new LlmUsage(1, 1));
            }
        };

        MemoryExtractor.ExtractionOutcome outcome = extractorWith(alwaysBad, properties).extract(chapter("正文。"), "第3章");

        assertThat(outcome.result()).isNull();
        assertThat(outcome.errorMessage()).contains("提取失败");
        assertThat(outcome.stats().retries()).isEqualTo(2);
    }

    @Test
    void budgetSmallerThanFixedOverheadIsRejected() {
        MemoryExtractionProperties tiny = new MemoryExtractionProperties(
                3, 100, 0, 2048, 0.2, 0, 0.7, 300, 200);

        assertThatThrownBy(() -> extractorWith(new MockLlmProvider(tokenCounter, objectMapper,
                new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0))),
                tiny)
                .extract(chapter("正文。"), "第3章"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预算过小");
    }

    private static String firstSentenceOfChapter(LlmRequest request) {
        return request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .reduce("", (a, b) -> b)
                .strip();
    }
}
