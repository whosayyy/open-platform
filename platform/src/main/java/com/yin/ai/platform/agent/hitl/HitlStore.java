package com.yin.ai.platform.agent.hitl;

import java.util.List;
import java.util.Optional;

/**
 * HITL 检查点存储 SPI — 管理待审批检查点的持久化。
 */
public interface HitlStore {

    /**
     * 保存检查点，进入待审批队列。
     */
    void save(HitlCheckpoint checkpoint);

    /**
     * 按任务 ID 查找待审批检查点。
     */
    Optional<HitlCheckpoint> getPendingByTask(String taskId);

    /**
     * 获取全部待审批检查点。
     */
    List<HitlCheckpoint> getPending();

    /**
     * 审批后删除检查点。
     */
    void remove(String taskId);

    /**
     * 获取待审批数量。
     */
    int pendingCount();
}
