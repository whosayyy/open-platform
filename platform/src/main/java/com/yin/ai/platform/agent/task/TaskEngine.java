package com.yin.ai.platform.agent.task;

/**
 * 平台级任务生命周期引擎 SPI。
 * <p>
 * 与 service 层 AgentTaskService 的关系：
 * - AgentTaskService 负责业务层（MySQL 记录、并发限制、WS 推送、配额）
 * - TaskEngine 负责运行时层（状态机、Redis 持久化、恢复、重试）
 * - AgentTaskService.submit() 内部调用 TaskEngine.create() + TaskEngine.start()
 * <p>
 * Redis 实现位于 Adapter 层：
 * {@link com.yin.ai.platform.adapter.runtime.persistence.RedisTaskEngine}。
 */
public interface TaskEngine {

    /** Acquire the idempotency lease for one concrete step attempt. */
    default boolean tryAcquireStepExecution(String taskId, String executionId, java.time.Duration ttl) {
        return true;
    }

    /**
     * 创建任务，状态 → CREATED，写入 Redis。
     *
     * @param request 创建请求
     * @return 任务句柄（含 taskId）
     */
    TaskHandle create(TaskCreateRequest request);

    /**
     * 启动任务。
     * SIMPLE/REACT: CREATED → RUNNING
     * PLAN_EXECUTE/DEEP_RESEARCH: CREATED → PLANNING
     */
    void start(String taskId);

    /**
     * 状态转换（带合法性校验）。
     *
     * @throws IllegalStateException 如果转换不合法
     */
    void transitionTo(String taskId, TaskState newState);

    /**
     * 获取任务快照（从 Redis 读取）。
     *
     * @return 快照，任务不存在时返回 null
     */
    TaskSnapshot getSnapshot(String taskId);

    /**
     * 保存步骤执行结果。
     */
    void saveStepResult(String taskId, String stepId, TaskStepResult result);

    default void saveStructuredStepResult(String taskId,
                                          com.yin.ai.platform.agent.pattern.StepResult result) {
    }

    /** Queue a user instruction for the next scheduler safe point. */
    default void enqueueInstruction(String taskId, String instruction) {
    }

    /** Atomically drain queued instructions in arrival order. */
    default java.util.List<String> drainInstructions(String taskId) {
        return java.util.List.of();
    }

    default StepExecutionRecord getStepExecutionRecord(String taskId, String stepId) {
        return null;
    }

    default void saveStepExecutionRecord(String taskId, StepExecutionRecord record) {
    }

    default java.util.List<StepExecutionRecord> listStepExecutionRecords(String taskId) {
        return java.util.List.of();
    }

    /**
     * 尝试恢复任务（从 Redis 重建上下文，仅 resumable 模式）。
     *
     * @return true 如果成功恢复并开始执行
     */
    boolean tryResume(String taskId);

    /**
     * 取消任务。
     *
     * @param taskId 任务 ID
     * @param reason 取消原因
     */
    void cancel(String taskId, String reason);

    /** List direct child tasks in creation order. */
    default java.util.List<TaskSnapshot> listChildren(String parentTaskId) {
        return java.util.List.of();
    }

    /** Cancel a task and every active descendant. */
    default void cancelTree(String taskId, String reason) {
        for (TaskSnapshot child : listChildren(taskId)) {
            cancelTree(child.taskId(), reason);
        }
        cancel(taskId, reason);
    }

    /** Update the team-level status without changing the task state machine. */
    default void updateTeamStatus(String taskId, String teamStatus) {
    }
}
