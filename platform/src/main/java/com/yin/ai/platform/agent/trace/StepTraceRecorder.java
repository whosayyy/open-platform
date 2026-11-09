package com.yin.ai.platform.agent.trace;

/**
 * Agent 全链路事件记录器 — 每完成一个关键环节（LLM 调用 / RAG 检索 / Memory 读写 / 工具调用），
 * 记录一条带 traceId 的台账，供按 traceId 回溯一次任务的完整执行履历。
 *
 * <p>platform 层定义接口，service 层提供 JDBC 实现（写入 ai_agent_step_trace）。
 * 无实现时降级为 no-op，不阻塞 Agent 主流程。
 *
 * <p>环节取值（stage）：
 * <ul>
 *   <li>{@code LLM_CALL} — LLM 推理调用</li>
 *   <li>{@code RAG_RETRIEVE} — 知识库检索</li>
 *   <li>{@code MEMORY_READ} — 记忆读取</li>
 *   <li>{@code MEMORY_WRITE} — 记忆写入</li>
 *   <li>{@code TOOL_CALL} — 工具调用</li>
 * </ul>
 */
public interface StepTraceRecorder {

    /**
     * 记录一次环节执行。
     *
     * @param taskId     任务 ID（8 位业务 ID，即 Agent 执行的 traceId）
     * @param stage      环节标识（见类注释）
     * @param detail     环节明细（工具名 / 检索查询 / 记忆操作摘要等）
     * @param durationMs 耗时（毫秒）
     */
    void record(String taskId, String stage, String detail, long durationMs);

    /** No-op 降级实现（未装配 JDBC 实现时不阻塞主流程） */
    StepTraceRecorder NOOP = (taskId, stage, detail, durationMs) -> {};
}
