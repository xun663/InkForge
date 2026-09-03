package com.inkforge.provider;

import java.util.Arrays;

/**
 * Immutable embedding vector. Defensive copies on both construction and access;
 * equality is value-based (array-safe), so tests can compare embeddings directly.
 */
public record Embedding(float[] values) {

    public Embedding {
        values = values == null ? new float[0] : values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    public int dimension() {
        return values.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Embedding other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
