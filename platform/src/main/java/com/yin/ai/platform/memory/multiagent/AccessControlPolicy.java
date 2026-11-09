package com.yin.ai.platform.memory.multiagent;

import com.yin.ai.platform.core.memory.MemoryType;

import java.util.Map;
import java.util.Set;

/**
 * 访问控制策略配置 — 定义哪些 Agent 可以对哪些 MemoryType 执行哪些操作。
 *
 * @param allowedAgentIds 允许访问的 Agent ID 集合（空 = 全部允许）
 * @param allowedTypes     允许访问的 Memory 类型（空 = 全部类型）
 * @param defaultReadOnly  默认是否只读
 * @param metadata         扩展配置
 */
public record AccessControlPolicy(
        Set<String> allowedAgentIds,
        Set<MemoryType> allowedTypes,
        boolean defaultReadOnly,
        Map<String, Object> metadata) {

    public AccessControlPolicy {
        allowedAgentIds = allowedAgentIds != null ? Set.copyOf(allowedAgentIds) : Set.of();
        allowedTypes = allowedTypes != null ? Set.copyOf(allowedTypes) : Set.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static AccessControlPolicy open() {
        return new AccessControlPolicy(Set.of(), Set.of(), false, Map.of());
    }

    public static AccessControlPolicy readOnly() {
        return new AccessControlPolicy(Set.of(), Set.of(), true, Map.of());
    }

    public boolean isAgentAllowed(String agentId) {
        return allowedAgentIds.isEmpty() || allowedAgentIds.contains(agentId);
    }

    public boolean isTypeAllowed(MemoryType type) {
        return allowedTypes.isEmpty() || allowedTypes.contains(type);
    }
}
