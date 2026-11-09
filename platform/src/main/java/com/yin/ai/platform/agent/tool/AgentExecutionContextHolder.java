package com.yin.ai.platform.agent.tool;

/**
 * Agent 执行上下文持有器 — ThreadLocal 保存当前 taskId。
 * <p>
 * Agent Control Tools 通过此持有器获取当前任务 ID，
 * 避免每个 Tool 都需要显式传入 taskId 参数。
 * <p>
 * 生命周期：Pattern 执行前 set → 执行后 clear。
 */
public final class AgentExecutionContextHolder {

    private static final ThreadLocal<String> TASK_ID = new ThreadLocal<>();

    private AgentExecutionContextHolder() {}

    public static void setCurrentTaskId(String taskId) {
        TASK_ID.set(taskId);
    }

    public static String currentTaskId() {
        String id = TASK_ID.get();
        if (id == null) {
            throw new IllegalStateException("No active task context. "
                    + "Ensure AgentExecutionContextHolder.setCurrentTaskId() is called before tool execution.");
        }
        return id;
    }

    public static void clear() {
        TASK_ID.remove();
    }
}
