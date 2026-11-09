package com.yin.ai.platform.memory.multiagent;

import com.yin.ai.platform.core.memory.MemoryId;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 共享 Memory 注册信息 — 描述 Memory 的所有权和共享配置。
 * <p>
 * 每个 MemoryId 在 Multi-Agent 场景下都有一个 SharedMemory 记录，
 * 由 {@link SharedMemoryManager} 管理。
 *
 * @param memoryId           Memory 唯一标识
 * @param ownerAgentId       Owner Agent ID
 * @param ownership          所有权级别
 * @param sharedWithAgentIds 被授权访问的 Agent ID 集合
 * @param metadata           扩展元数据
 * @param createdAt          创建时间
 */
public record SharedMemory(
        MemoryId memoryId,
        String ownerAgentId,
        OwnershipLevel ownership,
        Set<String> sharedWithAgentIds,
        Map<String, Object> metadata,
        Instant createdAt) {

    public SharedMemory {
        ownerAgentId = ownerAgentId != null ? ownerAgentId : "";
        ownership = ownership != null ? ownership : OwnershipLevel.EXCLUSIVE;
        sharedWithAgentIds = sharedWithAgentIds != null ? Set.copyOf(sharedWithAgentIds) : Set.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static SharedMemory exclusive(MemoryId memoryId, String ownerAgentId) {
        return new SharedMemory(memoryId, ownerAgentId, OwnershipLevel.EXCLUSIVE,
                Set.of(), Map.of(), Instant.now());
    }

    public SharedMemory withOwnership(OwnershipLevel newLevel) {
        return new SharedMemory(memoryId, ownerAgentId, newLevel,
                sharedWithAgentIds, metadata, createdAt);
    }

    public SharedMemory shareWith(String agentId) {
        if (sharedWithAgentIds.contains(agentId)) {
            return this;
        }
        Set<String> newSet = new java.util.HashSet<>(sharedWithAgentIds);
        newSet.add(agentId);
        return new SharedMemory(memoryId, ownerAgentId, ownership,
                newSet, metadata, createdAt);
    }

    /** 检查指定 agent 是否有读权限 */
    public boolean canRead(String agentId) {
        return switch (ownership) {
            case PUBLIC -> true;
            case EXCLUSIVE -> ownerAgentId.equals(agentId);
            case SHARED_READ, SHARED_WRITE ->
                    ownerAgentId.equals(agentId) || sharedWithAgentIds.contains(agentId);
        };
    }

    /** 检查指定 agent 是否有写权限 */
    public boolean canWrite(String agentId) {
        return switch (ownership) {
            case PUBLIC, SHARED_WRITE -> true;
            case EXCLUSIVE, SHARED_READ -> ownerAgentId.equals(agentId);
        };
    }
}
