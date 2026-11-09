package com.yin.ai.platform.memory.multiagent;

import com.yin.ai.platform.core.memory.MemoryId;

/**
 * 访问控制 SPI — 在 Agent 层面对 Memory 操作进行权限裁决。
 * <p>
 * 与 {@link SharedMemoryManager} 的关系：
 * <ul>
 *   <li>SharedMemoryManager — 管理"谁被授权了什么"（配置层）</li>
 *   <li>AccessControl — 在每次操作时裁决"这次操作是否允许"（执行层）</li>
 * </ul>
 */
public interface AccessControl {

    /**
     * 检查对指定 MemoryId 的读权限。
     *
     * @param memoryId 目标 Memory
     * @param agentId  请求 Agent ID
     * @return true 表示允许读取
     */
    boolean checkReadAccess(MemoryId memoryId, String agentId);

    /**
     * 检查对指定 MemoryId 的写权限。
     *
     * @param memoryId 目标 Memory
     * @param agentId  请求 Agent ID
     * @return true 表示允许写入
     */
    boolean checkWriteAccess(MemoryId memoryId, String agentId);

    /**
     * 检查 Agent 是否有管理权限（如创建/删除 Memory、修改策略等）。
     */
    boolean checkAdminAccess(String agentId);

    /** 设置访问控制策略（用于配置驱动） */
    default void setPolicy(AccessControlPolicy policy) {
        // no-op, subclasses override
    }
}
