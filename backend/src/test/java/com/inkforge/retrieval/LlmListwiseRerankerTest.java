package com.inkforge.retrieval;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmListwiseRerankerTest {

    private static final String LONG_TEXT = "长".repeat(500) + "原始尾部";

    private LlmListwiseReranker reranker;
    private LlmProvider mockProvider;
    private AtomicReference<LlmRequest> lastRequest;

    private final RetrievalProperties properties =
            new RetrievalProperties(30, 30, 30, 8, 60, "llm", 15, 200);

    @BeforeEach
    void setUp() {
        TokenCounter tokenCounter = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        ObjectMapper objectMapper = new ObjectMapper();
        mockProvider = new MockLlmProvider(tokenCounter, objectMapper,
                new LlmProperties("mock", "https://unused", "", "unused", 300, new LlmProperties.Mock(0)));
        reranker = new LlmListwiseReranker(mockProvider, catalog, properties, objectMapper);
        lastRequest = new AtomicReference<>();
    }

    private static RetrievalResult result(String chunkId, String text) {
        return new RetrievalResult(chunkId, "n1", 1, MemoryChunkType.EVENT, "src:" + chunkId, text, 1.0);
    }

    private LlmListwiseReranker withFlakyProvider(LlmProvider flaky) {
        return new LlmListwiseReranker(flaky, new ClasspathPromptCatalog(), properties, new ObjectMapper());
    }

    @Test
    void mockChainParsesNumbersAndMapsBack() {
        List<RetrievalResult> out = reranker.rerank("查询", List.of(
                result("a", "甲"), result("b", "乙"), result("c", "丙")), 3);

        // Mock 按输入顺序回显编号 → [1,2,3]
        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b", "c");
    }

    @Test
    void capsAtTopK() {
        List<RetrievalResult> out = reranker.rerank("查询", List.of(
                result("a", "甲"), result("b", "乙"), result("c", "丙")), 2);

        assertThat(out).hasSize(2);
    }

    @Test
    void moreThanMaxCandidatesAreNotSentToLlm() {
        List<RetrievalResult> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> result("c" + i, "候选" + i))
                .toList();

        reranker.rerank("查询", many, 8);
        // 验证 prompt 中候选数 ≤ 15：Mock 回显的编号最大为 15
        // （通过自定义 provider 抓取实际请求断言）
        LlmProvider capturing = new DelegatingProvider(mockProvider, lastRequest);
        new LlmListwiseReranker(capturing, new ClasspathPromptCatalog(), properties, new ObjectMapper())
                .rerank("查询", many, 8);

        String userPrompt = lastRequest.get().messages().get(1).content();
        assertThat(userPrompt).contains("[15]").doesNotContain("[16]");
    }

    @Test
    void candidateTextIsTruncatedToMaxCharsInPromptButOriginalUntouched() {
        LlmProvider capturing = new DelegatingProvider(mockProvider, lastRequest);
        new LlmListwiseReranker(capturing, new ClasspathPromptCatalog(), properties, new ObjectMapper())
                .rerank("查询", List.of(result("a", LONG_TEXT)), 1);

        String userPrompt = lastRequest.get().messages().get(1).content();
        // prompt 中截断到 200 chars + 省略号
        assertThat(userPrompt).doesNotContain("原始尾部").contains("…");
        // 原始 RetrievalResult 未被修改
        assertThat(result("a", LONG_TEXT).text()).isEqualTo(LONG_TEXT);
    }

    @Test
    void unparseableOutputThrowsRerankException() {
        LlmProvider bad = new DelegatingProvider(new LlmProvider() {
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
                return new LlmResponse("这不是数组", new LlmUsage(1, 1));
            }
        }, lastRequest);

        assertThatThrownBy(() -> withFlakyProvider(bad).rerank("q", List.of(result("a", "甲")), 1))
                .isInstanceOf(RerankException.class);
    }

    @Test
    void invalidNumberThrows() {
        LlmProvider bad = new DelegatingProvider(new LlmProvider() {
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
                return new LlmResponse("[9]", new LlmUsage(1, 1)); // 只有 1 个候选
            }
        }, lastRequest);

        assertThatThrownBy(() -> withFlakyProvider(bad).rerank("q", List.of(result("a", "甲")), 1))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("非法编号");
    }

    @Test
    void duplicateNumberThrows() {
        LlmProvider bad = new DelegatingProvider(new LlmProvider() {
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
                return new LlmResponse("[1,1]", new LlmUsage(1, 1));
            }
        }, lastRequest);

        assertThatThrownBy(() -> withFlakyProvider(bad).rerank("q",
                List.of(result("a", "甲"), result("b", "乙")), 2))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("重复编号");
    }

    @Test
    void emptyOutputThrows() {
        LlmProvider bad = new DelegatingProvider(new LlmProvider() {
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
                return new LlmResponse("[]", new LlmUsage(1, 1));
            }
        }, lastRequest);

        assertThatThrownBy(() -> withFlakyProvider(bad).rerank("q", List.of(result("a", "甲")), 1))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("协议不完整");
    }

    /** Captures the last LlmRequest while delegating to the real provider. */
    private static class DelegatingProvider implements LlmProvider {

        private final LlmProvider delegate;
        private final AtomicReference<LlmRequest> captured;

        DelegatingProvider(LlmProvider delegate, AtomicReference<LlmRequest> captured) {
            this.delegate = delegate;
            this.captured = captured;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String defaultModel() {
            return delegate.defaultModel();
        }

        @Override
        public Flux<ProviderStreamEvent> stream(LlmRequest request) {
            return delegate.stream(request);
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            captured.set(request);
            return delegate.complete(request);
        }
    }
}
