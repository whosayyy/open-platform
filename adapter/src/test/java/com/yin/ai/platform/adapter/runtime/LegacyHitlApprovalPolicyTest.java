package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.agent.hitl.InMemoryHitlStore;
import com.yin.ai.platform.agent.hitl.HitlCheckpoint;
import com.yin.ai.platform.agent.hitl.RiskLevel;
import com.yin.ai.platform.agent.hitl.ToolExecutionHitlPolicy;
import com.yin.ai.platform.core.agent.approval.ApprovalContext;
import com.yin.ai.platform.core.agent.approval.ApprovalRequest;
import com.yin.ai.platform.core.agent.approval.ApprovalRisk;
import com.yin.ai.platform.core.agent.plan.AgentPlan;
import com.yin.ai.platform.core.agent.plan.PlanStep;
import com.yin.ai.platform.core.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyHitlApprovalPolicyTest {

    @Test
    void mapsCoreStepToLegacyPolicyAndPendingStore() {
        InMemoryHitlStore store = new InMemoryHitlStore();
        LegacyHitlApprovalPolicy adapter = new LegacyHitlApprovalPolicy(
                new ToolExecutionHitlPolicy(Set.of("publish"), RiskLevel.HIGH), store);
        PlanStep step = PlanStep.pending("publish-step", "Publish result", "publish",
                Map.of("contentId", 42), List.of());
        AgentPlan plan = new AgentPlan("publish safely", List.of(step));

        ApprovalRequest request = adapter.evaluate(new ApprovalContext("task-1",
                "PLAN_EXECUTE", plan, step, 0, ExecutionContext.create("trace-1")))
                .orElseThrow();

        assertThat(request.risk()).isEqualTo(ApprovalRisk.HIGH);
        assertThat(request.context()).containsEntry("toolParams", Map.of("contentId", 42));
        assertThat(store.getPendingByTask("task-1").orElseThrow().approvalId())
                .isEqualTo(request.approvalId());
    }

    @Test
    void defaultsMissingLegacyRiskToMedium() {
        InMemoryHitlStore store = new InMemoryHitlStore();
        PlanStep step = PlanStep.pending("guarded", "Guarded action", "llm",
                Map.of(), List.of());
        AgentPlan plan = new AgentPlan("guarded work", List.of(step));
        LegacyHitlApprovalPolicy nullableRiskAdapter = new LegacyHitlApprovalPolicy(
                new com.yin.ai.platform.agent.hitl.HitlPolicy() {
                    @Override
                    public HitlCheckpoint evaluate(
                            com.yin.ai.platform.agent.planner.Step ignored,
                            com.yin.ai.platform.agent.AgentContext ignoredContext) {
                        return null;
                    }

                    @Override
                    public HitlCheckpoint evaluate(com.yin.ai.platform.agent.pattern.TaskStep ignored,
                                                   String taskId, int stepIndex) {
                        return new HitlCheckpoint("approval-1", taskId, stepIndex,
                                "guarded action", "legacy data", null, Map.of(), null, null);
                    }
                }, store);

        ApprovalRequest request = nullableRiskAdapter.evaluate(new ApprovalContext("task-1",
                "PLAN_EXECUTE", plan, step, 0, ExecutionContext.create("trace-1")))
                .orElseThrow();

        assertThat(request.risk()).isEqualTo(ApprovalRisk.MEDIUM);
    }
}
