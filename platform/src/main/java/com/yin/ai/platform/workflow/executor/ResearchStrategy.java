package com.yin.ai.platform.workflow.executor;

import java.util.List;
import java.util.Set;

/**
 * 研究步骤策略 — 定义如何规划查询、执行工具并合成产物。
 *
 * <p>LlmStepExecutor 适用于单轮结构化输出；研究步骤需要多工具调用，
 * 由 AgentStepExecutor 驱动，具体查询规划/工具映射/结果合成由业务实现本接口。
 */
public interface ResearchStrategy {

    /**
     * 研究查询规划的结构化输出契约（如 submitResearchQueries）。
     */
    StructuredOutputSpec plannerSpec();

    /**
     * 证据合成的结构化输出契约（如 submitEvidenceSet）。
     */
    StructuredOutputSpec synthesizerSpec();

    /**
     * 当前研究步骤允许访问的工具集。
     */
    Set<String> allowedTools();

    /**
     * 根据查询列表执行确定性工具调用（程序将 freshnessType 转换为 days）。
     */
    List<ResearchToolResult> runToolCalls(List<ResearchQuery> queries, StepExecutionContext context);
}
