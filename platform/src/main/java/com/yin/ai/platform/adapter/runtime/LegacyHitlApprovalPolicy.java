package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.agent.hitl.HitlCheckpoint;
import com.yin.ai.platform.agent.hitl.HitlPolicy;
import com.yin.ai.platform.agent.hitl.HitlStore;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.core.agent.approval.ApprovalContext;
import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.approval.ApprovalRequest;
import com.yin.ai.platform.core.agent.approval.ApprovalRisk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Maps the existing HITL policy and pending queue onto the pure-Java core port. */
public final class LegacyHitlApprovalPolicy implements ApprovalPolicy {

    private final HitlPolicy policy;
    private final HitlStore store;

    public LegacyHitlApprovalPolicy(HitlPolicy policy, HitlStore store) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Optional<ApprovalRequest> evaluate(ApprovalContext context) {
        TaskStep legacyStep = TaskStep.pending(context.step().id(),
                context.step().description(), context.step().executor(),
                context.step().dependsOn());
        HitlCheckpoint checkpoint = policy.evaluate(legacyStep,
                context.taskId(), context.stepIndex());
        if (checkpoint == null) return Optional.empty();
        store.save(checkpoint);
        Map<String, Object> approvalContext = checkpoint.context() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(checkpoint.context());
        approvalContext.putIfAbsent("toolParams", context.step().arguments());
        ApprovalRisk risk = checkpoint.riskLevel() == null
                ? ApprovalRisk.MEDIUM : ApprovalRisk.valueOf(checkpoint.riskLevel().name());
        return Optional.of(new ApprovalRequest(checkpoint.approvalId(),
                checkpoint.taskId(), context.step().id(), checkpoint.action(),
                checkpoint.rationale(), risk,
                Map.copyOf(approvalContext), checkpoint.createdAt(), checkpoint.expiresAt()));
    }
}
