package com.yin.ai.platform.agent.hitl;

import com.yin.ai.platform.agent.pattern.TaskStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionHitlPolicyV2Test {

    @Test
    void createsApprovalBeforeSensitivePlanStep() {
        ToolExecutionHitlPolicy policy = new ToolExecutionHitlPolicy();
        TaskStep step = TaskStep.pending("publish", "publish final content", "publishContent", List.of());

        HitlCheckpoint checkpoint = policy.evaluate(step, "task-1", 3);

        assertThat(checkpoint).isNotNull();
        assertThat(checkpoint.approvalId()).isNotBlank();
        assertThat(checkpoint.taskId()).isEqualTo("task-1");
        assertThat(checkpoint.context()).containsEntry("stepId", "publish");
        assertThat(checkpoint.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }
}
