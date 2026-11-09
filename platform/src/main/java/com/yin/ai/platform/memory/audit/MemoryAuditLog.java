package com.yin.ai.platform.memory.audit;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Memory 审计日志 — 记录每一次对 Memory 的访问、写入、修改、删除操作。
 * <p>
 * 企业级要求：
 * <ul>
 *   <li><b>可追溯</b>: 谁、何时、对哪个 Memory 做了什么</li>
 *   <li><b>不可篡改</b>: record 不可变，append-only</li>
 *   <li><b>合规</b>: 支持按用户/租户查询全部操作历史</li>
 *   <li><b>关联</b>: 通过 traceId 关联到原始 LLM 调用</li>
 * </ul>
 *
 * @param auditId     审计记录唯一 ID
 * @param actionType  操作类型（READ / APPEND / UPDATE / DELETE / CAS）
 * @param memoryId    目标 Memory ID
 * @param memoryType  目标 Memory 类型
 * @param agentId     执行操作的 Agent
 * @param userId      用户 ID
 * @param tenantId    租户 ID
 * @param traceId     链路追踪 ID（关联原始调用）
 * @param cellCount   涉及的 Cell 数量
 * @param timestamp   操作时间
 * @param metadata    扩展元数据（token 用量、耗时等）
 */
public record MemoryAuditLog(
        String auditId,
        AuditAction actionType,
        MemoryId memoryId,
        MemoryType memoryType,
        String agentId,
        Long userId,
        String tenantId,
        String traceId,
        int cellCount,
        Instant timestamp,
        Map<String, Object> metadata) {

    public MemoryAuditLog {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static MemoryAuditLog of(AuditAction action, MemoryId id, String agentId,
                                     Long userId, String tenantId, String traceId,
                                     int cellCount, Map<String, Object> metadata) {
        return new MemoryAuditLog(UUID.randomUUID().toString(), action, id,
                id != null ? id.type() : null, agentId, userId, tenantId,
                traceId, cellCount, Instant.now(), metadata);
    }

    /** 操作类型 */
    public enum AuditAction {
        READ,
        APPEND,
        BATCH_APPEND,
        UPDATE,
        DELETE,
        CAS_WRITE,
        CHECKPOINT_CREATE,
        CHECKPOINT_RESTORE,
        CONSOLIDATE,
        COMPRESS,
        EVICT,
        PROCEDURE_LEARNED,
        FACT_WRITTEN
    }
}
