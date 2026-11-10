package com.yin.ai.platform.gateway.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("SemanticCacheManager — 余弦相似度")
class SemanticCacheManagerTest {

    @Test
    @DisplayName("相同向量相似度为 1.0")
    void identicalVectors() {
        float[] a = {1.0f, 2.0f, 3.0f};
        double sim = SemanticCacheManager.cosineSimilarity(a, a);
        assertThat(sim).isCloseTo(1.0, within(0.0001));
    }

    @Test
    @DisplayName("正交向量相似度为 0")
    void orthogonalVectors() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};
        double sim = SemanticCacheManager.cosineSimilarity(a, b);
        assertThat(sim).isCloseTo(0.0, within(0.0001));
    }

    @Test
    @DisplayName("相反向量相似度为 -1.0")
    void oppositeVectors() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {-1.0f, -2.0f, -3.0f};
        double sim = SemanticCacheManager.cosineSimilarity(a, b);
        assertThat(sim).isCloseTo(-1.0, within(0.0001));
    }

    @Test
    @DisplayName("不同长度向量返回 0")
    void differentLengthVectors() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        double sim = SemanticCacheManager.cosineSimilarity(a, b);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    @DisplayName("零向量返回 0")
    void zeroVector() {
        float[] a = {0.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        double sim = SemanticCacheManager.cosineSimilarity(a, b);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    @DisplayName("典型语义相似向量")
    void typicalSemanticVectors() {
        float[] a = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        float[] b = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        double sim = SemanticCacheManager.cosineSimilarity(a, b);
        assertThat(sim).isCloseTo(1.0, within(0.0001));
    }

    @Test
    @DisplayName("部分相似向量")
    void partiallySimilarVectors() {
        float[] a = {0.5f, 0.5f, 0.5f, 0.5f};
        float[] b = {0.5f, 0.5f, -0.5f, -0.5f};
        double sim = SemanticCacheManager.cosineSimilarity(a, b);
        assertThat(sim).isCloseTo(0.0, within(0.0001));
    }
}
