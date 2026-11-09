package com.yin.ai.platform.agent.pattern;

import java.util.List;

/**
 * 执行计划 — PLAN_EXECUTE / DEEP_RESEARCH 模式的多步骤计划。
 * <p>
 * 由 Planner LLM 生成，通过 {@code create_task_plan} Tool 写入。
 * 步骤之间通过 dependsOn 形成 DAG，支持并行执行无依赖步骤。
 *
 * @param goal  任务目标
 * @param steps 有序步骤列表
 */
public record TaskPlan(
        String goal,
        List<TaskStep> steps
) {
    /** 获取指定索引的步骤 */
    public TaskStep stepAt(int index) {
        return index >= 0 && index < steps.size() ? steps.get(index) : null;
    }

    /** 获取下一个待执行步骤 */
    public TaskStep nextPending() {
        return steps.stream()
                .filter(TaskStep::isPending)
                .findFirst()
                .orElse(null);
    }

    /** 是否所有步骤都已完成（含 SKIPPED） */
    public boolean allDone() {
        return steps.stream().allMatch(s ->
                s.status() == StepStatus.COMPLETED || s.status() == StepStatus.SKIPPED);
    }

    /** 已完成步骤数 */
    public long completedCount() {
        return steps.stream().filter(TaskStep::isCompleted).count();
    }

    /** 总步骤数 */
    public int totalSteps() {
        return steps.size();
    }

    /** 返回更新了指定步骤后的新 Plan（不可变） */
    public TaskPlan withUpdatedStep(String stepId, TaskStep updated) {
        List<TaskStep> newSteps = steps.stream()
                .map(s -> s.id().equals(stepId) ? updated : s)
                .toList();
        return new TaskPlan(goal, newSteps);
    }
}
