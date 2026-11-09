package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单步执行台账记录 — 记录一次步骤执行的完整输入/输出与执行信息。
 * <p>
 * 与 {@link com.yin.ai.platform.agent.StepTrace} 的区别：StepTrace 是旧 ReAct 模式的薄轨迹，
 * 本类是统一模式（PlanExecute / DeepResearch）的富台账，捕获步骤输入、工具调用、
 * 重试次数、耗时等中间细节，随 {@link ContextSnapshot} 持久化、落库 {@code ai_agent_step_record}，
 * 供多轮延续按需读取。
 *
 * @param taskId      任务 ID
 * @param stepId      步骤 ID
 * @param stepIndex   步骤索引
 * @param description 步骤描述
 * @param executor    执行器标识（llm / view_artifact / web_search ...）
 * @param status      执行状态（COMPLETED / FAILED / SKIPPED）
 * @param inputs      步骤输入（已截断）
 * @param output      执行输出（已截断）
 * @param error       错误信息（失败时）
 * @param toolCalls   工具调用（executor 非 llm 时记录执行器名）
 * @param artifactIds 产生的产物 ID 列表
 * @param retryCount  实际尝试次数（含首次）
 * @param durationMs  执行耗时（毫秒）
 * @param startedAt   开始时间
 * @param endedAt     结束时间
 */
public record StepRecord(
        String taskId,
        String stepId,
        int stepIndex,
        String description,
        String executor,
        String status,
        Map<String, String> inputs,
        String output,
        String error,
        List<String> toolCalls,
        List<String> artifactIds,
        int retryCount,
        long durationMs,
        Instant startedAt,
        Instant endedAt) {

    /** 单个输入值最大长度（防 DB 膨胀） */
    private static final int INPUT_VALUE_MAX = 500;
    /** 步骤输出最大长度（完整内容走 MinIO artifact / 会话消息） */
    private static final int OUTPUT_MAX = 8000;

    /**
     * 从一次步骤执行的结果构建台账记录。
     *
     * @param taskId     任务 ID
     * @param step       步骤定义
     * @param stepIndex  步骤索引
     * @param inputs     dispatch 解析后的输入
     * @param result     执行结果
     * @param retryCount 实际尝试次数（含首次）
     * @param startedMs  开始时间戳（毫秒）
     */
    public static StepRecord from(String taskId, TaskStep step, int stepIndex,
                                  Map<String, String> inputs,
                                  StepExecutorResult result, int retryCount, long startedMs) {
        long now = System.currentTimeMillis();
        boolean success = result != null && result.success();
        String executor = step.executor() != null ? step.executor() : "llm";
        return new StepRecord(
                taskId,
                step.id(),
                stepIndex,
                step.description(),
                executor,
                success ? "COMPLETED" : "FAILED",
                truncateMap(inputs, INPUT_VALUE_MAX),
                success ? truncate(result.output(), OUTPUT_MAX) : null,
                success ? null : (result != null ? result.error() : "no result"),
                "llm".equals(executor) ? List.of() : List.of(executor),
                List.of(),
                retryCount,
                now - startedMs,
                Instant.ofEpochMilli(startedMs),
                Instant.ofEpochMilli(now));
    }

    private static Map<String, String> truncateMap(Map<String, String> inputs, int max) {
        if (inputs == null || inputs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : inputs.entrySet()) {
            if (e.getValue() == null) continue;
            out.put(e.getKey(), truncate(e.getValue(), max));
        }
        return Map.copyOf(out);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
