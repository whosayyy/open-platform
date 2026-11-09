package com.yin.ai.platform.agent;

/**
 * 进度回调 — 由 ReActLoop 在执行过程中实时推送进度事件，
 * 通过 AgentRuntimeAdapter 桥接到 AiProgressPublisher → WebSocket 前端。
 *
 * <p>与 {@link CheckpointCallback}（按步持久化）互补，
 * 此回调专注实时进度可见性（planning/acting/complete）。
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * @param agentId     Agent 实例 ID
     * @param event       当前事件（含 type/step/data）
     */
    void onEvent(String agentId, AgentEvent event);
}
