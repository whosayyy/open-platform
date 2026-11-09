package com.yin.ai.platform.agent.pattern;

import java.util.List;

/**
 * 执行计划中的单个步骤。
 *
 * @param id              步骤唯一标识（如 "step-1", "search", "write-report"）
 * @param description     步骤描述（供 LLM 和前端展示）
 * @param executor        执行器标识：skill 名 / tool 名 / "llm"（纯 LLM 生成）
 * @param dependsOn       依赖的前序步骤 ID 列表（DAG 拓扑）
 * @param status          当前状态
 * @param output          执行输出（完成后填充）
 * @param error           错误信息（失败时填充）
 * @param requiresMemory  [B-agentic] 该步骤是否需要自动注入历史 memory；默认 false（agentic Memory: 每步骤不再默认注入，按需 opt-in 或 LLM 主动调 recallMemory 工具）
 * @param reason          [UI 优化] LLM 给出的"为什么这么做"（前端待办展示）
 */
public record TaskStep(
        String id,
        String description,
        String executor,
        List<String> dependsOn,
        StepStatus status,
        String output,
        String error,
        boolean requiresMemory,
        String reason,
        StepType stepType
) {
    /** Compatibility constructor for plans persisted before Agent V2. */
    public TaskStep(String id, String description, String executor, List<String> dependsOn,
                    StepStatus status, String output, String error,
                    boolean requiresMemory, String reason) {
        this(id, description, executor, dependsOn, status, output, error,
                requiresMemory, reason, StepType.infer(executor, description));
    }

    /** 创建一个 PENDING 状态的新步骤（默认 requiresMemory=false，agentic Memory） */
    public static TaskStep pending(String id, String description, String executor, List<String> dependsOn) {
        return new TaskStep(id, description, executor,
                dependsOn != null ? dependsOn : List.of(),
                StepStatus.PENDING, null, null, false, null, StepType.infer(executor, description));
    }

    /** [UI 优化] 创建带"为什么"说明的新步骤 */
    public static TaskStep pending(String id, String description, String executor,
                                   List<String> dependsOn, String reason) {
        return new TaskStep(id, description, executor,
                dependsOn != null ? dependsOn : List.of(),
                StepStatus.PENDING, null, null, false, reason, StepType.infer(executor, description));
    }

    /** 返回状态更新后的新实例（不可变） */
    public TaskStep withStatus(StepStatus newStatus) {
        return new TaskStep(id, description, executor, dependsOn, newStatus, output, error, requiresMemory, reason, stepType);
    }

    public TaskStep withOutput(String output) {
        return new TaskStep(id, description, executor, dependsOn, StepStatus.COMPLETED, output, null, requiresMemory, reason, stepType);
    }

    public TaskStep withError(String error) {
        return new TaskStep(id, description, executor, dependsOn, StepStatus.FAILED, output, error, requiresMemory, reason, stepType);
    }

    /** [B] 显式标记本步骤需要 memory 注入 */
    public TaskStep withRequiresMemory(boolean v) {
        return new TaskStep(id, description, executor, dependsOn, status, output, error, v, reason, stepType);
    }

    public TaskStep withStepType(StepType value) {
        return new TaskStep(id, description, executor, dependsOn, status, output, error,
                requiresMemory, reason, value == null ? StepType.infer(executor, description) : value);
    }

    public boolean isCompleted() {
        return status == StepStatus.COMPLETED;
    }

    public boolean isPending() {
        return status == StepStatus.PENDING;
    }
}
