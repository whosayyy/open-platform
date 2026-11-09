package com.yin.ai.platform.memory.multiagent;

import com.yin.ai.platform.core.memory.MemoryId;

import java.util.Optional;

/**
 * 共享 Memory 管理器 SPI — Multi-Agent 场景下的 Memory 访问控制入口。
 * <p>
 * 职责：
 * <ul>
 *   <li>注册新的共享 Memory（设置 owner 和 ownership level）</li>
 *   <li>授权/撤销 Agent 对特定 Memory 的访问</li>
 *   <li>运行时权限检查（canRead / canWrite）</li>
 * </ul>
 */
public interface SharedMemoryManager {

    /** 注册 Memory 并设置所有权 */
    SharedMemory register(MemoryId id, String ownerAgentId, OwnershipLevel level);

    /** 将 Memory 共享给指定 Agent */
    SharedMemory shareWith(MemoryId id, String targetAgentId);

    /** 撤销某 Agent 对 Memory 的访问权限 */
    SharedMemory revoke(MemoryId id, String targetAgentId);

    /** 更改所有权级别 */
    SharedMemory changeOwnership(MemoryId id, OwnershipLevel newLevel);

    /** 检查 Agent 是否有读权限 */
    boolean canRead(MemoryId id, String agentId);

    /** 检查 Agent 是否有写权限 */
    boolean canWrite(MemoryId id, String agentId);

    /** 查找 Memory 的共享信息 */
    Optional<SharedMemory> lookup(MemoryId id);

    /** 取消注册 */
    void unregister(MemoryId id);
}
