package com.yin.ai.platform.core.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 原子记忆单元 — Memory Runtime 的通用货币。
 * <p>
 * 所有 Memory 类型（Working/Episodic/Semantic/Procedural）都存储为 MemoryCell，
 * 差异在于如何组织、索引和检索，而非 Cell 结构本身。
 * <p>
 * 设计为不可变 record：所有 with* 方法返回新实例，线程安全。
 * version 字段用于乐观锁并发控制，sequenceNumber 用于 Timeline 排序。
 *
 * @param cellId         全局唯一标识
 * @param memoryType     所属 Memory 类型
 * @param content        实际内容（文本/JSON）
 * @param attributes     结构化元数据（键值对）
 * @param timestamp      创建/观测时间
 * @param sequenceNumber 单调递增序号（Timeline 排序）
 * @param vector         向量嵌入（可选，用于语义搜索）
 * @param tags           自定义标签（过滤用）
 * @param parentCellId   父 Cell ID（因果/派生关系）
 * @param version        乐观锁版本号
 */
public record MemoryCell(
        MemoryCellId cellId,
        MemoryType memoryType,
        String content,
        Map<String, Object> attributes,
        Instant timestamp,
        long sequenceNumber,
        Optional<MemoryVector> vector,
        Map<String, String> tags,
        MemoryCellId parentCellId,
        int version) {

    public MemoryCell {
        content = content != null ? content : "";
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        tags = tags != null ? Map.copyOf(tags) : Map.of();
        vector = vector != null ? vector : Optional.empty();
    }

    public static MemoryCell of(MemoryCellId cellId, MemoryType memoryType, String content) {
        return new MemoryCell(cellId, memoryType, content, Map.of(),
                Instant.now(), 0, Optional.empty(), Map.of(), null, 1);
    }

    public static MemoryCell of(MemoryCellId cellId, MemoryType memoryType, String content,
                                 Map<String, Object> attributes) {
        return new MemoryCell(cellId, memoryType, content, attributes,
                Instant.now(), 0, Optional.empty(), Map.of(), null, 1);
    }

    public MemoryCell withVector(MemoryVector vector) {
        return new MemoryCell(cellId, memoryType, content, attributes,
                timestamp, sequenceNumber, Optional.of(vector), tags, parentCellId, version);
    }

    public MemoryCell withContent(String newContent) {
        return new MemoryCell(cellId, memoryType, newContent, attributes,
                timestamp, sequenceNumber, vector, tags, parentCellId, version);
    }

    public MemoryCell withSequenceNumber(long sequenceNumber) {
        return new MemoryCell(cellId, memoryType, content, attributes,
                timestamp, sequenceNumber, vector, tags, parentCellId, version);
    }

    public MemoryCell withTag(String key, String value) {
        Map<String, String> newTags = new java.util.HashMap<>(tags);
        newTags.put(key, value);
        return new MemoryCell(cellId, memoryType, content, attributes,
                timestamp, sequenceNumber, vector, newTags, parentCellId, version);
    }

    public MemoryCell withAttribute(String key, Object value) {
        Map<String, Object> newAttrs = new java.util.HashMap<>(attributes);
        newAttrs.put(key, value);
        return new MemoryCell(cellId, memoryType, content, newAttrs,
                timestamp, sequenceNumber, vector, tags, parentCellId, version);
    }

    public MemoryCell withVersion(int newVersion) {
        return new MemoryCell(cellId, memoryType, content, attributes,
                timestamp, sequenceNumber, vector, tags, parentCellId, newVersion);
    }

    public MemoryCell withParent(MemoryCellId parentId) {
        return new MemoryCell(cellId, memoryType, content, attributes,
                timestamp, sequenceNumber, vector, tags, parentId, version);
    }
}
