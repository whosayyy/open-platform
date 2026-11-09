package com.yin.ai.platform.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MemoryVector")
class MemoryVectorTest {

    @Test
    @DisplayName("cosine similarity of identical vectors is 1.0")
    void identicalVectors() {
        MemoryVector v = MemoryVector.of(new float[]{1, 2, 3}, "test");
        assertThat(v.cosineSimilarity(v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("cosine similarity of orthogonal vectors is 0.0")
    void orthogonalVectors() {
        MemoryVector a = MemoryVector.of(new float[]{1, 0, 0}, "test");
        MemoryVector b = MemoryVector.of(new float[]{0, 1, 0}, "test");
        assertThat(a.cosineSimilarity(b)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("cosine similarity of opposite vectors is -1.0")
    void oppositeVectors() {
        MemoryVector a = MemoryVector.of(new float[]{1, 0}, "test");
        MemoryVector b = MemoryVector.of(new float[]{-1, 0}, "test");
        assertThat(a.cosineSimilarity(b)).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("cosine similarity with zero vector returns 0")
    void zeroVector() {
        MemoryVector a = MemoryVector.of(new float[]{0, 0, 0}, "test");
        MemoryVector b = MemoryVector.of(new float[]{1, 2, 3}, "test");
        assertThat(a.cosineSimilarity(b)).isEqualTo(0);
    }

    @Test
    @DisplayName("dimension mismatch throws exception")
    void dimensionMismatch() {
        MemoryVector a = MemoryVector.of(new float[]{1, 2}, "test");
        MemoryVector b = MemoryVector.of(new float[]{1, 2, 3}, "test");
        assertThatThrownBy(() -> a.cosineSimilarity(b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions mismatch");
    }
}
