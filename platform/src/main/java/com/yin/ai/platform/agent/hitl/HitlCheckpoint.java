package com.yin.ai.platform.agent.hitl;

import java.time.Instant;
import java.util.Map;

/**
 * HITL 审批检查点 — 当 Agent 执行到需要人工审批的操作时产生。
 *
 * @param taskId    关联任务 ID
 * @param step      当前步骤序号
 * @param action    待审批的操作描述
 * @param rationale 需要审批的原因
 * @param riskLevel 风险等级
 * @param context   审批上下文（如工具名、参数摘要）
 * @param createdAt 创建时间
 * @param expiresAt 过期时间，过期后自动拒绝
 */
public record HitlCheckpoint(
        String approvalId,
        String taskId,
        int step,
        String action,
        String rationale,
        RiskLevel riskLevel,
        Map<String, Object> context,
        Instant createdAt,
        Instant expiresAt) {

    public HitlCheckpoint {
        if (approvalId == null || approvalId.isBlank()) approvalId = java.util.UUID.randomUUID().toString();
    }

    /** Compatibility constructor for checkpoints serialized before approvalId existed. */
    public HitlCheckpoint(String taskId, int step, String action, String rationale,
                          RiskLevel riskLevel, Map<String, Object> context,
                          Instant createdAt, Instant expiresAt) {
        this(null, taskId, step, action, rationale, riskLevel, context, createdAt, expiresAt);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public static HitlCheckpoint of(String taskId, int step, String action, String rationale,
                                     RiskLevel riskLevel, Map<String, Object> context) {
        Instant now = Instant.now();
        return new HitlCheckpoint(null, taskId, step, action, rationale, riskLevel, context,
                now, now.plusSeconds(300)); // 5 分钟过期
    }
}
