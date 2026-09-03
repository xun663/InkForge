package com.inkforge.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, zero-key embedding provider for pipeline correctness — NOT semantic
 * quality. Same input → identical vector; different inputs → (usually) different
 * vectors; texts sharing n-grams land closer (so relative-similarity tests work).
 * The vector is a multi-probe character n-gram feature vector, L2-normalized.
 *
 * <p>Documented limitation: Mock embedding quality is NOT real embedding quality;
 * semantic quality only exists with a real EmbeddingProvider.
 */
public class MockEmbeddingProvider implements EmbeddingProvider {

    public static final String NAME = "mock";

    private final int dimension;

    public MockEmbeddingProvider(EmbeddingProperties properties) {
        this.dimension = properties.dimension();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Embedding embed(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        float[] vector = new float[dimension];
        addGrams(vector, normalized, 1, 2); // unigram + bigram — shared substrings stay close
        normalize(vector);
        return new Embedding(vector);
    }

    @Override
    public List<Embedding> embedBatch(List<String> texts) {
        List<Embedding> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    private void addGrams(float[] vector, String text, int minLen, int maxLen) {
        for (int len = minLen; len <= maxLen; len++) {
            for (int i = 0; i + len <= text.length(); i++) {
                String gram = text.substring(i, i + len);
                int hash = gram.hashCode();
                // multi-probe: spread each gram over several slots to reduce collisions
                vector[Math.floorMod(hash, dimension)] += 1.0f;
                vector[Math.floorMod(hash / 31, dimension)] += 1.0f;
                vector[Math.floorMod(hash / 97, dimension)] += 1.0f;
            }
        }
    }

    private void normalize(float[] vector) {
        double sumSquares = 0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0) {
            return; // zero vector stays zero — cosine treats it as "no signal", never NaN
        }
        double norm = Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
