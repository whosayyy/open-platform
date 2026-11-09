package com.yin.ai.platform.core.memory;

import java.util.Arrays;

/**
 * 向量嵌入 — 用于 Semantic Memory 的语义相似度搜索。
 * <p>
 * 显式覆盖 equals/hashCode 是因为 float[] 默认使用引用比较，
 * 必须用 Arrays.equals / Arrays.hashCode 进行值比较。
 */
public record MemoryVector(float[] values, String model, int dimension) {

    public static MemoryVector of(float[] values, String model) {
        return new MemoryVector(values, model, values.length);
    }

    /** 余弦相似度 — 返回值在 [-1, 1] 区间，零向量返回 0 */
    public double cosineSimilarity(MemoryVector other) {
        if (this.values.length != other.values.length) {
            throw new IllegalArgumentException("Vector dimensions mismatch: "
                    + this.values.length + " vs " + other.values.length);
        }
        float dot = 0;
        float normA = 0;
        float normB = 0;
        for (int i = 0; i < values.length; i++) {
            dot += this.values[i] * other.values[i];
            normA += this.values[i] * this.values[i];
            normB += other.values[i] * other.values[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryVector that)) return false;
        return dimension == that.dimension && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return 31 * dimension + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "MemoryVector[dim=" + dimension + ", model=" + model + "]";
    }
}
