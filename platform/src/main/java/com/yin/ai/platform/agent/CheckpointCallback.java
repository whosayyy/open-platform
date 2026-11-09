package com.yin.ai.platform.agent;

/**
 * 检查点回调 — 由 ReActLoop 在每个 step 完成后调用，持久化当前执行状态。
 *
 * <p>wkc-ai-platform 定义接口，wkc-ai-service 实现并委托给 {@code AiAgentCheckpointService}。
 */
@FunctionalInterface
public interface CheckpointCallback {

    /**
     * 在 ReActLoop 每步完成后调用，通知外部保存检查点。
     *
     * @param taskId 任务 ID
     * @param step   当前步骤序号（1-based）
     * @param ctx    当前 AgentContext（由实现侧决定序列化方式）
     */
    void onStepComplete(String taskId, int step, AgentContext ctx);
}
