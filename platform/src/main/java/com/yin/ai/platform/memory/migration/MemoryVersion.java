package com.yin.ai.platform.memory.migration;

import java.time.Instant;

/**
 * Memory 数据版本 — 用于跟踪 Schema 变更和迁移历史。
 *
 * @param version     版本号（单调递增）
 * @param description 版本变更描述
 * @param createdAt   版本创建时间
 */
public record MemoryVersion(
        int version,
        String description,
        Instant createdAt) {

    public MemoryVersion {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static MemoryVersion of(int version, String description) {
        return new MemoryVersion(version, description, Instant.now());
    }

    /** V1 初始版本 */
    public static MemoryVersion v1() {
        return new MemoryVersion(1, "Initial schema: MemoryCell + MemoryId core model", Instant.now());
    }

    public boolean isNewerThan(MemoryVersion other) {
        return this.version > other.version;
    }

    public boolean isOlderThan(MemoryVersion other) {
        return this.version < other.version;
    }
}
