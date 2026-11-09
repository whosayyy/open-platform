package com.yin.ai.platform.core.memory.procedural;

import java.util.Map;

/**
 * Procedure 步骤 — 任务执行过程中的单步操作记录。
 * <p>
 * 不可变 record：preconditions 和 postconditions 用于成功率统计和前置条件匹配。
 * successRate 由 ProcedureExecutor 在多次执行后聚合更新。
 *
 * @param seq             步骤序号
 * @param type            步骤类型
 * @param toolName        工具名称（TOOL_CALL 时使用）
 * @param params          调用参数
 * @param preconditions   前置条件（键值对）
 * @param postconditions  后置条件（键值对）
 * @param successRate     历史成功率 [0.0, 1.0]
 * @param metadata        扩展元数据
 */
public record ProcedureStep(
        int seq,
        ProcedureStepType type,
        String toolName,
        Map<String, Object> params,
        Map<String, Object> preconditions,
        Map<String, Object> postconditions,
        double successRate,
        Map<String, Object> metadata) {

    public ProcedureStep {
        toolName = toolName != null ? toolName : "";
        params = params != null ? Map.copyOf(params) : Map.of();
        preconditions = preconditions != null ? Map.copyOf(preconditions) : Map.of();
        postconditions = postconditions != null ? Map.copyOf(postconditions) : Map.of();
        successRate = Math.clamp(successRate, 0.0, 1.0);
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static ProcedureStep think(int seq, String description) {
        return new ProcedureStep(seq, ProcedureStepType.THINK, "",
                Map.of("description", description), Map.of(), Map.of(), 1.0, Map.of());
    }

    public static ProcedureStep toolCall(int seq, String toolName, Map<String, Object> params) {
        return new ProcedureStep(seq, ProcedureStepType.TOOL_CALL, toolName,
                params, Map.of(), Map.of(), 1.0, Map.of());
    }

    public static ProcedureStep observe(int seq, String observation) {
        return new ProcedureStep(seq, ProcedureStepType.OBSERVE, "",
                Map.of("observation", observation), Map.of(), Map.of(), 1.0, Map.of());
    }

    public static ProcedureStep act(int seq, String description) {
        return new ProcedureStep(seq, ProcedureStepType.ACT, "",
                Map.of("description", description), Map.of(), Map.of(), 1.0, Map.of());
    }

    public ProcedureStep withSuccessRate(double rate) {
        return new ProcedureStep(seq, type, toolName, params, preconditions,
                postconditions, rate, metadata);
    }
}
