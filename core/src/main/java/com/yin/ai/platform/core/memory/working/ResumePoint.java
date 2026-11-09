package com.yin.ai.platform.core.memory.working;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 恢复点 — 在关键操作前创建的断点，用于故障后恢复。
 * <p>
 * 捕获当前 planNodeId（定位执行位置）、变量快照和已产生的中间结果。
 * 通过 WorkingMemoryStore 持久化到 Redis L2，Crash 后通过 {@link WorkingMemoryManager#resume} 恢复。
 */
public record ResumePoint(
        String label,
        String planNodeId,
        Map<String, Object> variables,
        List<IntermediateResult> resultsUpToThisPoint,
        Instant createdAt) {

    public ResumePoint {
        variables = variables != null ? Map.copyOf(variables) : Map.of();
        resultsUpToThisPoint = resultsUpToThisPoint != null ? List.copyOf(resultsUpToThisPoint) : List.of();
    }

    public static ResumePoint of(String label, String planNodeId,
                                  Map<String, Object> variables,
                                  List<IntermediateResult> results) {
        return new ResumePoint(label, planNodeId, variables, results, Instant.now());
    }
}
