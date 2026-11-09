package com.yin.ai.platform.agent.event;

import com.yin.ai.platform.agent.pattern.Artifact;
import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.StepResult;
import com.yin.ai.platform.agent.pattern.StepSummary;
import com.yin.ai.platform.agent.pattern.TaskSummary;
import com.yin.ai.platform.gateway.ModelExecutionOptions;

import java.time.Instant;
import java.util.Map;

/**
 * 统一 Agent 事件 — 所有模式共用的事件模型。
 * <p>
 * 通过 SSE 推送到前端，同时写入 Redis Stream 用于断线恢复。
 * 前端通过 Last-Event-ID 实现事件重放。
 *
 * @param id        事件唯一 ID（Redis Stream entry ID）
 * @param taskId    所属任务 ID
 * @param type      事件类型
 * @param stepId    关联步骤 ID（可为 null）
 * @param payload   事件载荷（结构化数据）
 * @param timestamp 事件时间
 */
public record AgentEvent(
        String id,
        String taskId,
        TaskEventType type,
        String stepId,
        Map<String, Object> payload,
        Instant timestamp
) {
    // === 工厂方法 ===

    public static AgentEvent taskCreated(String taskId, String mode) {
        return of(taskId, TaskEventType.TASK_CREATED, null, Map.of("mode", mode));
    }

    public static AgentEvent taskPlanning(String taskId) {
        return of(taskId, TaskEventType.TASK_PLANNING, null, Map.of());
    }

    public static AgentEvent taskStarted(String taskId) {
        return of(taskId, TaskEventType.TASK_RUNNING, null, Map.of());
    }

    public static AgentEvent taskWaiting(String taskId, String phase, String approvalId) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("phase", phase);
        if (approvalId != null) payload.put("approvalId", approvalId);
        return of(taskId, TaskEventType.TASK_WAITING, null, payload);
    }

    public static AgentEvent taskCompleted(String taskId) {
        return of(taskId, TaskEventType.TASK_COMPLETED, null, Map.of());
    }

    public static AgentEvent taskSummary(String taskId, TaskSummary summary) {
        return of(taskId, TaskEventType.TASK_SUMMARY, null, Map.of(
                "title", summary.title(),
                "content", summary.content(),
                "findings", summary.findings(),
                "artifactRefs", summary.artifactRefs(),
                "nextAction", summary.nextAction()));
    }

    /** Final user-visible delivery for a completed task. */
    public static AgentEvent taskCompleted(String taskId, String result) {
        return of(taskId, TaskEventType.TASK_COMPLETED, null,
                result != null && !result.isBlank() ? Map.of("result", result) : Map.of());
    }

    public static AgentEvent taskCompleted(String taskId, String result,
                                           String artifactKey, String artifactUrl,
                                           String artifactName) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        if (result != null && !result.isBlank()) payload.put("result", result);
        if (artifactKey != null && !artifactKey.isBlank()) payload.put("artifactKey", artifactKey);
        if (artifactUrl != null && !artifactUrl.isBlank()) payload.put("artifactUrl", artifactUrl);
        if (artifactName != null && !artifactName.isBlank()) payload.put("artifactName", artifactName);
        return of(taskId, TaskEventType.TASK_COMPLETED, null, payload);
    }

    public static AgentEvent taskFailed(String taskId, String error) {
        return of(taskId, TaskEventType.TASK_FAILED, null,
                Map.of("error", error == null || error.isBlank() ? "unknown_runtime_error" : error));
    }

    public static AgentEvent stepStarted(String taskId, String stepId, String description) {
        return of(taskId, TaskEventType.STEP_STARTED, stepId,
                Map.of("description", shortTitle(description)));
    }

    public static AgentEvent stepCompleted(String taskId, String stepId, String output) {
        return stepCompleted(taskId, stepId, output, false);
    }

    public static AgentEvent stepCompleted(String taskId, String stepId, String output,
                                           boolean publishIntermediateArtifact) {
        String normalized = output == null ? "" : output.trim();
        String summary = summarize(normalized);
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("output", summary);
        payload.put("outputChars", normalized.length());
        // Intermediate publication is an explicit LLM-backed decision. Never
        // infer it from length/formatting and accidentally expose tool JSON.
        if (publishIntermediateArtifact && !normalized.isBlank()) {
            payload.put("artifactContent", normalized);
            payload.put("artifactDecision", "llm");
        }
        return of(taskId, TaskEventType.STEP_COMPLETED, stepId, payload);
    }

    public static AgentEvent stepProgress(String taskId, String stepId, int current, int total,
                                          String message) {
        return of(taskId, TaskEventType.STEP_PROGRESS, stepId,
                Map.of("current", current, "total", total, "message", message));
    }

    public static AgentEvent stepResult(String taskId, StepResult result) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("executionId", result.executionId());
        payload.put("success", result.success());
        payload.put("value", result.value());
        if (result.resultRef() != null) payload.put("resultRef", result.resultRef());
        payload.put("artifactRefs", result.artifactRefs());
        payload.put("evidenceRefs", result.evidenceRefs());
        if (result.contextRef() != null) payload.put("contextRef", result.contextRef());
        if (result.error() != null) payload.put("error", result.error());
        return of(taskId, TaskEventType.STEP_RESULT, result.stepId(), payload);
    }

    public static AgentEvent stepSummary(String taskId, StepSummary summary) {
        return of(taskId, TaskEventType.STEP_SUMMARY, summary.stepId(), Map.of(
                "title", summary.title(),
                "content", summary.content(),
                "findings", summary.findings(),
                "nextAction", summary.nextAction()));
    }

    /** Stream user-facing Markdown only; raw reasoning and worker output are forbidden. */
    public static AgentEvent stepSummaryDelta(String taskId, String stepId,
                                              String delta, int ordinal) {
        return of(taskId, TaskEventType.STEP_SUMMARY_DELTA, stepId, Map.of(
                "delta", delta == null ? "" : delta,
                "contentType", "text/markdown",
                "ordinal", Math.max(0, ordinal)));
    }

    public static AgentEvent toolStarted(String taskId, String stepId, String toolName, String label) {
        return toolEvent(taskId, TaskEventType.TOOL_STARTED, stepId, toolName, label, null);
    }

    public static AgentEvent toolCompleted(String taskId, String stepId, String toolName, String label) {
        return toolEvent(taskId, TaskEventType.TOOL_COMPLETED, stepId, toolName, label, null);
    }

    public static AgentEvent toolFailed(String taskId, String stepId, String toolName,
                                        String label, String error) {
        return toolEvent(taskId, TaskEventType.TOOL_FAILED, stepId, toolName, label, error);
    }

    private static AgentEvent toolEvent(String taskId, TaskEventType type, String stepId,
                                        String toolName, String label, String error) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("toolName", toolName == null ? "tool" : toolName);
        payload.put("label", label == null || label.isBlank() ? "工具活动" : shortTitle(label));
        if (error != null && !error.isBlank()) payload.put("error", error);
        return of(taskId, type, stepId, payload);
    }

    public static AgentEvent reasoningStarted(String taskId, String stepId,
                                               ModelExecutionOptions options) {
        return of(taskId, TaskEventType.REASONING_STARTED, stepId, reasoningPayload(options));
    }

    public static AgentEvent reasoningCompleted(String taskId, String stepId,
                                                 ModelExecutionOptions options, long tokensUsed) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>(reasoningPayload(options));
        payload.put("tokensUsed", Math.max(0, tokensUsed));
        return of(taskId, TaskEventType.REASONING_COMPLETED, stepId, payload);
    }

    private static Map<String, Object> reasoningPayload(ModelExecutionOptions options) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        if (options != null) {
            payload.put("requestedMode", options.requestedReasoning().name());
            payload.put("effectiveMode", options.effectiveReasoning().name());
            if (options.fallbackReason() != null) payload.put("fallbackReason", options.fallbackReason());
        }
        return payload;
    }

    public static AgentEvent approvalRequired(String taskId, String stepId, String approvalId,
                                               String action, String riskLevel) {
        return of(taskId, TaskEventType.APPROVAL_REQUIRED, stepId, Map.of(
                "approvalId", approvalId, "action", action, "riskLevel", riskLevel));
    }

    public static AgentEvent approvalResolved(String taskId, String stepId, String approvalId,
                                               String decision, String resolvedBy) {
        return of(taskId, TaskEventType.APPROVAL_RESOLVED, stepId, Map.of(
                "approvalId", approvalId, "decision", decision, "resolvedBy", resolvedBy));
    }

    public static AgentEvent stepFailed(String taskId, String stepId, String error) {
        return of(taskId, TaskEventType.STEP_FAILED, stepId, Map.of("error", error));
    }

    public static AgentEvent stepSkipped(String taskId, String stepId, String reason) {
        return of(taskId, TaskEventType.STEP_SKIPPED, stepId,
                Map.of("reason", reason == null ? "skipped by policy" : reason));
    }

    public static AgentEvent planCreated(String taskId, TaskPlan plan) {
        java.util.List<Map<String, Object>> steps = planStages(plan);
        return of(taskId, TaskEventType.PLAN_CREATED, null,
                Map.of("goal", plan.goal(), "totalSteps", plan.totalSteps(), "steps", steps));
    }

    /** User-facing explanation emitted immediately after the planner commits a plan. */
    public static AgentEvent planSummary(String taskId, TaskPlan plan, boolean resumed) {
        String summary = buildPlanSummary(plan, resumed);
        return of(taskId, TaskEventType.PLAN_SUMMARY, null,
                Map.of("summary", summary,
                        "totalSteps", plan.totalSteps(),
                        "completedSteps", plan.completedCount(),
                        "resumed", resumed));
    }

    public static AgentEvent planUpdated(String taskId, TaskPlan plan) {
        return planUpdated(taskId, plan, false);
    }

    public static AgentEvent planUpdated(String taskId, TaskPlan plan, boolean restored) {
        java.util.List<Map<String, Object>> stages = planStages(plan);
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("goal", plan.goal());
        payload.put("totalSteps", plan.totalSteps());
        payload.put("completedSteps", plan.completedCount());
        payload.put("steps", stages);
        if (restored) payload.put("restored", true);
        return of(taskId, TaskEventType.PLAN_UPDATED, null, payload);
    }

    private static java.util.List<Map<String, Object>> planStages(TaskPlan plan) {
        return plan.steps().stream()
                .<Map<String, Object>>map(step -> {
                    java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("id", step.id());
                    item.put("title", shortTitle(step.description()));
                    item.put("summary", shortTitle(step.description()));
                    item.put("executor", step.executor());
                    item.put("stepType", step.stepType().name());
                    item.put("status", step.status().name().toLowerCase(java.util.Locale.ROOT));
                    item.put("dependsOn", step.dependsOn() == null ? java.util.List.of() : step.dependsOn());
                    if (step.reason() != null && !step.reason().isBlank()) {
                        item.put("reason", step.reason());
                    }
                    return item;
                })
                .toList();
    }

    private static String buildPlanSummary(TaskPlan plan, boolean resumed) {
        java.util.List<String> titles = plan.steps().stream()
                .map(TaskStep -> shortTitle(TaskStep.description()))
                .filter(title -> title != null && !title.isBlank())
                .toList();
        StringBuilder text = new StringBuilder(resumed
                ? "我已找到上次执行的断点。已完成的步骤和中间产物会保留，接下来继续："
                : "基于当前已有的信息，我规划了这些步骤：");
        for (int i = 0; i < titles.size(); i++) {
            if (i > 0) text.append(i == titles.size() - 1 ? "，最后" : "，然后");
            else text.append("先");
            text.append(titles.get(i));
        }
        text.append("。");
        return text.toString();
    }

    /** Dynamic context ledger update emitted after each worker step. */
    public static AgentEvent contextUpdated(String taskId, String stepId,
                                             int version, String summary,
                                             String artifactKey, String artifactUrl,
                                             int outputChars) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("version", version);
        payload.put("summary", summarize(summary));
        payload.put("outputChars", outputChars);
        if (artifactKey != null && !artifactKey.isBlank()) payload.put("artifactKey", artifactKey);
        if (artifactUrl != null && !artifactUrl.isBlank()) payload.put("artifactUrl", artifactUrl);
        return of(taskId, TaskEventType.CONTEXT_UPDATED, stepId, payload);
    }

    public static AgentEvent artifactUpdate(String taskId, Artifact artifact) {
        return of(taskId, TaskEventType.ARTIFACT_CREATED, artifact.stepId(),
                Map.of("name", artifact.name(), "type", artifact.type()));
    }

    public static AgentEvent referencesFound(String taskId, String stepId,
                                             java.util.List<Map<String, Object>> references) {
        return of(taskId, TaskEventType.REFERENCES_FOUND, stepId,
                Map.of("references", references == null ? java.util.List.of() : references));
    }

    public static AgentEvent progress(String taskId, int current, int total, String message) {
        return of(taskId, TaskEventType.PROGRESS, null,
                Map.of("current", current, "total", total, "message", message));
    }

    public static AgentEvent teamEvent(String parentTaskId, TaskEventType type,
                                       String childTaskId, String role,
                                       String mode, String status,
                                       String summary, long tokensUsed) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("parentTaskId", parentTaskId);
        if (childTaskId != null) payload.put("childTaskId", childTaskId);
        if (role != null) payload.put("role", role);
        if (mode != null) payload.put("mode", mode);
        if (status != null) payload.put("status", status);
        if (summary != null && !summary.isBlank()) payload.put("summary", summarize(summary));
        if (tokensUsed >= 0) payload.put("tokensUsed", tokensUsed);
        return of(parentTaskId, type, childTaskId, payload);
    }

    private static AgentEvent of(String taskId, TaskEventType type, String stepId, Map<String, Object> payload) {
        return new AgentEvent(null, taskId, type, stepId, payload, Instant.now());
    }

    /** Keep step-complete events suitable for an activity feed, never a dump of the worker output. */
    private static String summarize(String output) {
        if (output == null || output.isBlank()) {
            return "步骤已完成";
        }
        String compact = output.replaceAll("\\s+", " ").trim();
        int sentenceEnd = -1;
        for (char marker : new char[]{'。', '！', '!', '？', '?', '.'}) {
            int index = compact.indexOf(marker);
            if (index > 0 && (sentenceEnd < 0 || index < sentenceEnd)) {
                sentenceEnd = index + 1;
            }
        }
        String summary = sentenceEnd > 0 ? compact.substring(0, sentenceEnd) : compact;
        if (summary.length() > 240) {
            summary = summary.substring(0, 240) + "…";
        }
        return summary;
    }

    private static String shortTitle(String output) {
        String summary = summarize(output);
        if (summary.length() <= 96) return summary;
        int boundary = summary.lastIndexOf(' ', 96);
        return summary.substring(0, boundary > 40 ? boundary : 96) + "…";
    }
}
