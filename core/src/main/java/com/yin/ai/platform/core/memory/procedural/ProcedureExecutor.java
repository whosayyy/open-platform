package com.yin.ai.platform.core.memory.procedural;

import java.util.UUID;

/**
 * Procedure 执行器 — 从任务执行结果中学习 Procedure。
 * <p>
 * 学习触发条件（由调用方判断）：
 * <ol>
 *   <li>任务最终状态为 COMPLETED</li>
 *   <li>步骤数 ≥ minStepsForLearning（默认 3）</li>
 *   <li>工具调用错误率 ≤ maxErrorRate（默认 0.3）</li>
 * </ol>
 * <p>
 * 提取逻辑：遍历任务执行记录中的 PlanNode 序列，提取为 ProcedureStep 列表。
 * <p>
 * 注意：当前版本仅提供模板方法（record steps），实际从 Agent Task 中
 * 提取 PlanNode 的集成逻辑推迟到 Phase 5 与 Consolidation 一起实现。
 */
public class ProcedureExecutor {

    private final int minStepsForLearning;
    private final double maxErrorRate;

    public ProcedureExecutor() {
        this(3, 0.3);
    }

    public ProcedureExecutor(int minStepsForLearning, double maxErrorRate) {
        this.minStepsForLearning = minStepsForLearning;
        this.maxErrorRate = maxErrorRate;
    }

    /**
     * 从步骤列表创建新的 Procedure（RECORDING 状态）。
     * 调用方负责提供从实际任务执行记录中提取的 ProcedureStep 列表。
     *
     * @param goal  任务目标
     * @param steps 提取的步骤列表
     * @return 新 Procedure（RECORDING 状态）
     */
    public Procedure recordFromSteps(String goal, java.util.List<ProcedureStep> steps) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        if (steps == null || steps.size() < minStepsForLearning) {
            throw new IllegalArgumentException(
                    "At least " + minStepsForLearning + " steps required, got "
                            + (steps != null ? steps.size() : 0));
        }

        long errorSteps = steps.stream()
                .filter(s -> s.successRate() < 0.5)
                .count();
        double errorRate = (double) errorSteps / steps.size();
        if (errorRate > maxErrorRate) {
            throw new IllegalArgumentException(
                    "Error rate " + String.format("%.2f", errorRate)
                            + " exceeds max " + maxErrorRate);
        }

        String id = "proc-" + UUID.randomUUID();
        return Procedure.record(id, goal).withSteps(steps);
    }

    public int minStepsForLearning() {
        return minStepsForLearning;
    }

    public double maxErrorRate() {
        return maxErrorRate;
    }
}
