package com.yin.ai.platform.agent;

import java.util.List;
import java.util.Optional;

/**
 * 任务恢复回调 — 由 TaskManager 在心跳扫描时调用，查询检查点以支持任务恢复。
 *
 * <p>wkc-ai-platform 定义接口，wkc-ai-service 实现并委托给 {@code AiAgentCheckpointService}。
 */
public interface TaskRecoveryCallback {

    /**
     * 获取指定任务的最后一个检查点 JSON。
     *
     * @param taskId 任务 ID
     * @return 最后一个检查点的 stateJson（若存在）
     */
    Optional<String> getLatestCheckpoint(String taskId);

    /**
     * 获取指定任务的所有检查点。
     *
     * @param taskId 任务 ID
     * @return 所有检查点的 stateJson 列表（按 step 升序）
     */
    List<String> getCheckpoints(String taskId);

    /**
     * 删除指定任务的所有检查点（任务完成后调用）。
     *
     * @param taskId 任务 ID
     */
    void deleteCheckpoints(String taskId);
}
