package com.yin.ai.platform.memory.cost;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Instant;
import java.util.Map;

/**
 * Memory 用量记录 — 记录每次 Memory 操作的资源消耗。
 * <p>
 * 企业级计费维度：
 * <ul>
 *   <li>Embedding Token 消耗（embedding 模型调用）</li>
 *   <li>Consolidation Token 消耗（LLM 摘要/提取）</li>
 *   <li>存储空间（Redis 内存 / MySQL 磁盘 / Milvus 向量存储）</li>
 *   <li>API 调用次数（读/写/检索）</li>
 * </ul>
 *
 * @param recordId         记录唯一 ID
 * @param tenantId         租户 ID
 * @param userId           用户 ID
 * @param memoryType       操作涉及的 Memory 类型
 * @param operationType    操作类型
 * @param inputTokens      输入 Token 数（LLM/Embedding 调用）
 * @param outputTokens     输出 Token 数
 * @param storageBytes     存储消耗（近似值）
 * @param cellCount        涉及的 Cell 数量
 * @param durationMs       操作耗时
 * @param timestamp        操作时间
 * @param metadata         扩展元数据（模型名称、向量维度等）
 */
public record MemoryUsageRecord(
        String recordId,
        String tenantId,
        Long userId,
        MemoryType memoryType,
        OperationType operationType,
        long inputTokens,
        long outputTokens,
        long storageBytes,
        int cellCount,
        long durationMs,
        Instant timestamp,
        Map<String, Object> metadata) {

    public MemoryUsageRecord {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static MemoryUsageRecord of(String tenantId, Long userId, MemoryType memoryType,
                                        OperationType op, long inputTokens, long outputTokens,
                                        long storageBytes, int cellCount, long durationMs,
                                        Map<String, Object> metadata) {
        return new MemoryUsageRecord(
                java.util.UUID.randomUUID().toString(), tenantId, userId, memoryType,
                op, inputTokens, outputTokens, storageBytes, cellCount, durationMs,
                Instant.now(), metadata);
    }

    /** 总 Token 消耗 */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /** 操作类型 */
    public enum OperationType {
        APPEND,
        READ,
        RETRIEVE,
        EMBED,
        CONSOLIDATE,
        COMPRESS,
        FACT_EXTRACT,
        PROCEDURE_LEARN,
        CHECKPOINT,
        DELETE
    }
}
