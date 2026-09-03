package com.inkforge.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmbeddingProviderTest {

    private final EmbeddingProvider provider =
            new MockEmbeddingProvider(new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120));

    private static double cosine(Embedding a, Embedding b) {
        float[] av = a.values();
        float[] bv = b.values();
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < av.length; i++) {
            dot += (double) av[i] * bv[i];
            na += (double) av[i] * av[i];
            nb += (double) bv[i] * bv[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Test
    void sameInputProducesIdenticalVector() {
        Embedding a = provider.embed("方源与白凝冰在青茅山相遇");
        Embedding b = provider.embed("方源与白凝冰在青茅山相遇");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void dimensionMatchesConfig() {
        assertThat(provider.embed("文本").dimension()).isEqualTo(1024);
    }

    @Test
    void differentInputsUsuallyDiffer() {
        assertThat(provider.embed("方源与白凝冰在青茅山相遇"))
                .isNotEqualTo(provider.embed("林默拔剑斩向血魔"));
    }

    @Test
    void batchMatchesIndividualEmbeddingsInOrder() {
        List<String> texts = List.of("甲", "乙", "丙");
        List<Embedding> batch = provider.embedBatch(texts);

        assertThat(batch).hasSize(3);
        for (int i = 0; i < texts.size(); i++) {
            assertThat(batch.get(i)).isEqualTo(provider.embed(texts.get(i)));
        }
    }

    @Test
    void sharedNGramsRankCloserThanUnrelated() {
        // 共享 n-gram 的文本应比无关文本更接近（相对排序，非语义质量声明）
        Embedding a = provider.embed("方源与白凝冰在青茅山相遇");
        Embedding related = provider.embed("方源 白凝冰 青茅山");
        Embedding unrelated = provider.embed("林默拔剑斩向血魔");

        double relatedSimilarity = cosine(a, related);
        double unrelatedSimilarity = cosine(a, unrelated);

        assertThat(relatedSimilarity).isGreaterThan(unrelatedSimilarity);
        assertThat(relatedSimilarity).isGreaterThan(0);
    }

    @Test
    void vectorsAreL2NormalizedAndFinite() {
        Embedding e = provider.embed("归一化检查");
        float[] values = e.values();
        double norm = 0;
        for (float v : values) {
            norm += (double) v * v;
            assertThat(Float.isFinite(v)).isTrue();
        }
        assertThat(Math.abs(Math.sqrt(norm) - 1.0)).isLessThan(1e-5);
    }
}
