package com.yin.ai.platform.agent.hitl;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.pattern.TaskStep;

import java.util.Map;
import java.util.Set;

/**
 * 基于工具名称的 HITL 策略 — 当 Agent 调用特定高危工具时触发审批。
 * <p>
 * 默认高危工具集可在构造时覆盖。
 */
public class ToolExecutionHitlPolicy implements HitlPolicy {

    private static final Set<String> DEFAULT_SENSITIVE_TOOLS = Set.of(
            "draftPublish", "publishContent", "deleteContent", "sendNotification",
            "batchUpdate", "exportData", "modifyUserPermission"
    );

    private final Set<String> sensitiveTools;
    private final RiskLevel defaultRiskLevel;

    public ToolExecutionHitlPolicy() {
        this(DEFAULT_SENSITIVE_TOOLS, RiskLevel.HIGH);
    }

    public ToolExecutionHitlPolicy(Set<String> sensitiveTools, RiskLevel defaultRiskLevel) {
        this.sensitiveTools = sensitiveTools;
        this.defaultRiskLevel = defaultRiskLevel;
    }

    @Override
    public HitlCheckpoint evaluate(Step step, AgentContext ctx) {
        if (!step.isToolCall() || !sensitiveTools.contains(step.tool())) {
            return null;
        }
        return HitlCheckpoint.of(
                ctx.getExternalTaskId() != null ? ctx.getExternalTaskId() : ctx.getAgentId(),
                ctx.getCurrentStep(),
                step.tool(),
                "调用高危工具: " + step.tool(),
                defaultRiskLevel,
                Map.of(
                        "toolName", step.tool(),
                        "toolParams", step.toolParams() != null ? step.toolParams() : Map.of(),
                        "stepDescription", step.description() != null ? step.description() : "",
                        "agentId", ctx.getAgentId()
                )
        );
    }

    @Override
    public HitlCheckpoint evaluate(TaskStep step, String taskId, int stepIndex) {
        String executor = step == null ? null : step.executor();
        if (executor == null || !sensitiveTools.contains(executor)) return null;
        return HitlCheckpoint.of(taskId, stepIndex, executor,
                "调用高危工具: " + executor, defaultRiskLevel,
                Map.of("toolName", executor,
                        "stepId", step.id(),
                        "stepDescription", step.description() == null ? "" : step.description()));
    }
}
