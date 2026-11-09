package com.yin.ai.platform.core.memory.procedural;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Procedure — 可复用的任务完成过程，Procedural Memory 的核心单元。
 * <p>
 * 设计为不可变 record：所有 with* 方法返回新实例。
 * 状态机遵循 {@link ProcedureState} 转换规则，通过 {@link #transitionTo(ProcedureState)} 驱动。
 * <p>
 * 学习流程：
 * <pre>
 * Task 成功 + 多步骤 + 低错误率
 *   → ProcedureExecutor.recordFromTask()
 *     → 提取 PlanNode 序列
 *       → Procedure(RECORDING)
 *         → 人工/Auto 验证 → VERIFIED
 *           → 首次复用成功 → ACTIVE
 * </pre>
 *
 * @param id               唯一标识
 * @param goal             目标描述（用于匹配）
 * @param state            生命周期状态
 * @param steps            步骤列表
 * @param totalExecutions  总执行次数
 * @param avgDurationMs    平均执行时长（ms）
 * @param tags             匹配标签
 * @param createdAt        创建时间
 * @param updatedAt        最后更新时间
 * @param metadata         扩展元数据
 */
public record Procedure(
        String id,
        String goal,
        ProcedureState state,
        List<ProcedureStep> steps,
        long totalExecutions,
        long avgDurationMs,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> metadata) {

    public Procedure {
        goal = goal != null ? goal : "";
        state = state != null ? state : ProcedureState.RECORDING;
        steps = steps != null ? List.copyOf(steps) : List.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        createdAt = createdAt != null ? createdAt : Instant.now();
        updatedAt = updatedAt != null ? updatedAt : createdAt;
    }

    public static Procedure record(String id, String goal) {
        return new Procedure(id, goal, ProcedureState.RECORDING,
                List.of(), 0, 0, List.of(), Instant.now(), Instant.now(), Map.of());
    }

    /** 状态转换 — 返回新实例 */
    public Procedure transitionTo(ProcedureState target) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + state + " to " + target);
        }
        return new Procedure(id, goal, target, steps, totalExecutions,
                avgDurationMs, tags, createdAt, Instant.now(), metadata);
    }

    /** 设置步骤列表 */
    public Procedure withSteps(List<ProcedureStep> newSteps) {
        return new Procedure(id, goal, state, List.copyOf(newSteps),
                totalExecutions, avgDurationMs, tags, createdAt, Instant.now(), metadata);
    }

    /** 添加单个步骤 */
    public Procedure addStep(ProcedureStep step) {
        List<ProcedureStep> newSteps = new ArrayList<>(steps);
        newSteps.add(step);
        return withSteps(newSteps);
    }

    /** 记录一次执行结果 */
    public Procedure recordExecution(long durationMs, boolean success) {
        long newTotal = totalExecutions + 1;
        long newAvg = (avgDurationMs * totalExecutions + durationMs) / newTotal;
        Map<String, Object> updated = new java.util.HashMap<>(metadata);
        long successes = number(updated.get("successCount")) + (success ? 1 : 0);
        long failures = number(updated.get("failureCount")) + (success ? 0 : 1);
        updated.put("successCount", successes);
        updated.put("failureCount", failures);
        updated.put("successRate", newTotal > 0 ? successes / (double) newTotal : 0.0);
        return new Procedure(id, goal, state, steps, newTotal,
                newAvg, tags, createdAt, Instant.now(), updated);
    }

    public Procedure recordExecution(long durationMs, boolean success, double cost,
                                     String failurePattern) {
        Procedure base = recordExecution(durationMs, success);
        Map<String, Object> updated = new java.util.HashMap<>(base.metadata());
        double totalCost = decimal(updated.get("totalCost")) + Math.max(0, cost);
        updated.put("totalCost", totalCost);
        updated.put("averageCost", base.totalExecutions() > 0
                ? totalCost / base.totalExecutions() : 0.0);
        if (!success && failurePattern != null && !failurePattern.isBlank()) {
            java.util.List<String> patterns = new java.util.ArrayList<>();
            Object previous = updated.get("failurePatterns");
            if (previous instanceof java.util.Collection<?> values) {
                values.forEach(value -> patterns.add(String.valueOf(value)));
            }
            patterns.add(failurePattern);
            updated.put("failurePatterns", java.util.List.copyOf(patterns));
        }
        return base.withMetadata(updated);
    }

    public Procedure withMetadata(Map<String, Object> newMetadata) {
        return new Procedure(id, goal, state, steps, totalExecutions, avgDurationMs,
                tags, createdAt, Instant.now(), newMetadata);
    }

    /** 添加匹配标签 */
    public Procedure withTag(String tag) {
        List<String> newTags = new ArrayList<>(tags);
        newTags.add(tag);
        return new Procedure(id, goal, state, steps, totalExecutions,
                avgDurationMs, newTags, createdAt, Instant.now(), metadata);
    }

    /** 步骤数量 */
    public int stepCount() {
        return steps.size();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
