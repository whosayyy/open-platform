package com.yin.ai.platform.agent.task;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.Artifact;
import com.yin.ai.platform.agent.pattern.ContextSnapshot;
import com.yin.ai.platform.agent.pattern.TaskPlan;

/**
 * 上下文持久化 SPI — AgentExecutionContext 的 Redis 读写。
 * <p>
 * 设计原则：
 * - 写操作异步（不阻塞 Pattern 执行主线程）
 * - 读操作同步（恢复时需要立即可用）
 * - Key 设计：ai:task:{taskId}:ctx:{field}
 */
public interface ContextStore {

    /** 异步保存单个变量 */
    void saveVariableAsync(String taskId, String key, Object value);

    /** 异步保存执行计划 */
    void savePlanAsync(String taskId, TaskPlan plan);

    /** 异步保存产物 */
    void saveArtifactAsync(String taskId, Artifact artifact);

    /** 保存完整快照（用于中断恢复） */
    void saveSnapshot(String taskId, ContextSnapshot snapshot);

    /** 从 Redis 恢复完整上下文（同步） */
    AgentExecutionContext restore(String taskId);

    /** 读取完整快照（任务完成后读取台账/状态用，同步） */
    ContextSnapshot loadSnapshot(String taskId);

    /** 删除任务上下文（任务完成后的清理，可延迟） */
    void cleanup(String taskId);
}
