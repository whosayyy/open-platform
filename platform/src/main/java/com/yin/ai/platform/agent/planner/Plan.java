package com.yin.ai.platform.agent.planner;

import java.util.Collections;
import java.util.List;

/**
 * Agent 执行计划 — 有序步骤列表 + 直接结果。
 * <p>
 * 如果 {@code result} 非 null，表示无需执行步骤的直接答案（如简单问候）。
 * 否则，按 {@code steps()} 顺序执行。
 * 如果 {@code error} 非 null，表示规划阶段失败（如 LLM 返回格式异常）。
 */
public record Plan(List<Step> steps, String result, String error) {

    public Plan {
        steps = steps != null ? Collections.unmodifiableList(steps) : Collections.emptyList();
    }

    public boolean hasSteps() {
        return !steps.isEmpty();
    }

    public boolean isDirectResult() {
        return result != null;
    }

    public boolean isError() {
        return error != null;
    }

    public static Plan ofSteps(List<Step> steps) {
        return new Plan(steps, null, null);
    }

    public static Plan direct(String result) {
        return new Plan(Collections.emptyList(), result, null);
    }

    public static Plan empty() {
        return new Plan(Collections.emptyList(), null, null);
    }

    public static Plan error(String message) {
        return new Plan(Collections.emptyList(), null, message);
    }
}
