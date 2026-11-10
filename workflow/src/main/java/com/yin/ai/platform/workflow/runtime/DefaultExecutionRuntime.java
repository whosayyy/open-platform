package com.yin.ai.platform.workflow.runtime;

import com.yin.ai.platform.workflow.event.ExecutionEvent;
import com.yin.ai.platform.workflow.event.ExecutionEventPublisher;
import com.yin.ai.platform.workflow.event.ExecutionEventType;
import com.yin.ai.platform.workflow.executor.StepExecutionContext;
import com.yin.ai.platform.workflow.executor.StepExecutionResult;
import com.yin.ai.platform.workflow.executor.StepExecutorRegistry;
import com.yin.ai.platform.workflow.hitl.HumanInputDecision;
import com.yin.ai.platform.workflow.hitl.HumanInputPolicy;
import com.yin.ai.platform.workflow.load.WorkflowRegistry;
import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.StepRun;
import com.yin.ai.platform.workflow.model.StepStatus;
import com.yin.ai.platform.workflow.model.TaskRequest;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.TaskStatus;
import com.yin.ai.platform.workflow.model.UserInput;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import com.yin.ai.platform.workflow.prompt.StepContext;
import com.yin.ai.platform.workflow.prompt.StepContextBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认执行运行时 — 唯一驱动流程推进的组件。
 *
 * <p>循环：找 READY Step → 执行 → 保存 Artifact → 激活下游 → 人工策略评估 → 继续/暂停。
 * 重试、超时由本类依据 StepDefinition.RetryPolicy/Timeout 控制。
 */
@Slf4j
@Component
public class DefaultExecutionRuntime implements ExecutionRuntime {

    private static final HumanInputPolicy PROCEED_POLICY = (task, context) -> HumanInputDecision.proceed();

    private final WorkflowTaskStore store;
    private final WorkflowRegistry workflowRegistry;
    private final StepScheduler scheduler;
    private final RetryManager retryManager;
    private final StepExecutorRegistry executorRegistry;
    private final ExecutionEventPublisher eventPublisher;
    private final StepContextBuilder stepContextBuilder;
    private final ObjectProvider<HumanInputPolicy> humanInputPolicyProvider;
    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();

    public DefaultExecutionRuntime(WorkflowTaskStore store,
                                   WorkflowRegistry workflowRegistry,
                                   StepScheduler scheduler,
                                   RetryManager retryManager,
                                   StepExecutorRegistry executorRegistry,
                                   ExecutionEventPublisher eventPublisher,
                                   StepContextBuilder stepContextBuilder,
                                   ObjectProvider<HumanInputPolicy> humanInputPolicyProvider,
                                   ObjectMapper objectMapper) {
        this.store = store;
        this.workflowRegistry = workflowRegistry;
        this.scheduler = scheduler;
        this.retryManager = retryManager;
        this.executorRegistry = executorRegistry;
        this.eventPublisher = eventPublisher;
        this.stepContextBuilder = stepContextBuilder;
        this.humanInputPolicyProvider = humanInputPolicyProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskRun start(WorkflowDefinition workflow, TaskRequest request) {
        TaskRun task = new TaskRun();
        task.setTaskRunId(UUID.randomUUID().toString());
        task.setConversationId(request.conversationId());
        task.setUserId(request.userId());
        task.setWorkflowName(workflow.getName());
        task.setWorkflowVersion(workflow.getVersion());
        task.setStatus(TaskStatus.CREATED);
        task.setExplicitPublishRequested(request.explicitPublishRequested());
        task.setStageByStageMode(request.stageByStageMode());
        task.setGoal(request.goal());
        task.setContext(new HashMap<>(request.contextOrEmpty()));
        task.setTraceId(request.context() != null && request.context().get("traceId") instanceof String t ? t
                : UUID.randomUUID().toString());
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        store.saveTaskRun(task);

        eventPublisher.publish(event(task, ExecutionEventType.TASK_CREATED,
                Map.of("goal", request.goal() == null ? "" : request.goal())));

        List<StepRun> initializedSteps = scheduler.initialize(task, workflow, store);
        Map<String, Object> planPayload = new HashMap<>();
        planPayload.put("workflow", workflow.getName());
        planPayload.put("workflowVersion", workflow.getVersion());
        planPayload.put("totalSteps", initializedSteps.size());
        planPayload.put("stageTotal", initializedSteps.size());
        planPayload.put("steps", workflow.getSteps().stream().map(this::planStep).toList());
        eventPublisher.publish(event(task, ExecutionEventType.PLAN_CREATED, planPayload));

        task.setStatus(TaskStatus.RUNNING);
        task.touch();
        store.updateTaskRun(task);
        eventPublisher.publish(event(task, ExecutionEventType.TASK_STARTED,
                Map.of("workflow", workflow.getName())));
        return task;
    }

    @Override
    public void execute(String taskRunId) {
        TaskRun task = store.loadTaskRun(taskRunId);
        if (task == null) {
            return;
        }
        if (task.isTerminal() || task.getStatus() == TaskStatus.WAITING_INPUT) {
            return;
        }
        WorkflowDefinition workflow = workflowRegistry.get(task.getWorkflowName());
        while (task.getStatus() == TaskStatus.RUNNING) {
            StepRun stepRun = scheduler.findNextReadyStep(task, workflow, store);
            if (stepRun == null) {
                finishTask(task, workflow);
                break;
            }
            executeStep(task, workflow, stepRun);
        }
    }

    @Override
    public void resume(String taskRunId, UserInput userInput) {
        TaskRun task = store.loadTaskRun(taskRunId);
        if (task == null || task.getStatus() != TaskStatus.WAITING_INPUT) {
            return;
        }
        if (userInput != null) {
            task.getContext().put("lastUserInput", userInput.content());
            task.getContext().put("lastUserInputAt", Instant.now().toString());
        }
        task.setStatus(TaskStatus.RUNNING);
        task.setWaitingInputReason(null);
        task.touch();
        store.updateTaskRun(task);
        execute(taskRunId);
    }

    @Override
    public void cancel(String taskRunId) {
        TaskRun task = store.loadTaskRun(taskRunId);
        if (task == null || task.isTerminal()) {
            return;
        }
        for (StepRun run : store.listStepRuns(taskRunId)) {
            if (!run.getStatus().isTerminal()) {
                run.setStatus(StepStatus.CANCELLED);
                store.saveStepRun(run);
            }
        }
        task.setStatus(TaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        task.touch();
        store.updateTaskRun(task);
        eventPublisher.publish(event(task, ExecutionEventType.TASK_CANCELLED, Map.of()));
    }

    @Override
    public TaskRun getTask(String taskRunId) {
        return store.loadTaskRun(taskRunId);
    }

    private void executeStep(TaskRun task, WorkflowDefinition workflow, StepRun stepRun) {
        StepDefinition step = workflow.step(stepRun.getStepId());
        if (step == null) {
            stepRun.setStatus(StepStatus.FAILED);
            stepRun.setErrorCode("NO_STEP_DEF");
            stepRun.setErrorMessage("step definition not found: " + stepRun.getStepId());
            store.saveStepRun(stepRun);
            failTask(task, stepRun.getStepId());
            return;
        }

        task.setStatus(TaskStatus.RUNNING);
        task.setCurrentStepId(step.getId());
        task.touch();
        store.updateTaskRun(task);

        stepRun.setStatus(StepStatus.RUNNING);
        stepRun.setAttempt(stepRun.getAttempt() + 1);
        stepRun.setStartedAt(Instant.now());
        store.saveStepRun(stepRun);
        Map<String, Object> stepStartedPayload = stageMeta(workflow, step.getId(), step.getName());
        stepStartedPayload.put("stepId", step.getId());
        stepStartedPayload.put("stepName", step.getName() == null ? step.getId() : step.getName());
        stepStartedPayload.put("etaSeconds", estimateEtaSeconds(task, workflow));
        eventPublisher.publish(event(task, ExecutionEventType.STEP_STARTED, stepStartedPayload));

        List<Artifact> inputs = loadInputs(task, step);
        StepContext prompt = stepContextBuilder.build(task, step, workflow, inputs, null);
        Set<String> allowedTools = prompt.allowedTools();
        StepExecutionContext context = new StepExecutionContext(
                task, stepRun, step, workflow, inputs, allowedTools, prompt, null);

        StepExecutionResult result;
        try {
            result = executorRegistry.get(step.getExecutorType()).execute(context);
        } catch (Exception e) {
            log.error("[WF-RUNTIME] step {} execute error taskRunId={}", step.getId(), task.getTaskRunId(), e);
            result = StepExecutionResult.fail("EXECUTION_ERROR", e.getMessage());
        }

        stepRun.setDurationMs(Duration.between(stepRun.getStartedAt(), Instant.now()).toMillis());

        if (result.needInput()) {
            stepRun.setStatus(StepStatus.SUCCEEDED);
            stepRun.setCompletedAt(Instant.now());
            store.saveStepRun(stepRun);
            Map<String, Object> stepCompletedPayload = stageMeta(workflow, step.getId(), step.getName());
            stepCompletedPayload.put("stepId", step.getId());
            eventPublisher.publish(event(task, ExecutionEventType.STEP_COMPLETED, stepCompletedPayload));
            pauseTask(task, result.waitingReason(), step.getId());
            return;
        }
        if (!result.success()) {
            handleStepFailure(task, workflow, stepRun, step, result);
            return;
        }
        handleStepSuccess(task, workflow, stepRun, step, result, context);
    }

    private void handleStepFailure(TaskRun task, WorkflowDefinition workflow, StepRun stepRun, StepDefinition step, StepExecutionResult result) {
        stepRun.setErrorCode(result.errorCode());
        stepRun.setErrorMessage(result.errorMessage());
        if (retryManager.shouldRetry(stepRun, step.getRetry())) {
            stepRun.setStatus(StepStatus.READY);
            store.saveStepRun(stepRun);
            long backoff = retryManager.backoffMillis(stepRun, step.getRetry());
            log.warn("[WF-RUNTIME] step {} failed, retrying attempt={} taskRunId={}",
                    step.getId(), stepRun.getAttempt(), task.getTaskRunId());
            eventPublisher.publish(event(task, ExecutionEventType.STEP_RETRYING,
                    Map.of("stepId", step.getId(), "attempt", stepRun.getAttempt(),
                            "errorCode", safe(result.errorCode()))));
            if (backoff > 0) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }
        stepRun.setStatus(StepStatus.FAILED);
        stepRun.setCompletedAt(Instant.now());
        store.saveStepRun(stepRun);
        Map<String, Object> stepFailedPayload = stageMeta(workflow, step.getId(), step.getName());
        stepFailedPayload.put("stepId", step.getId());
        stepFailedPayload.put("errorCode", result.errorCode() == null ? "" : result.errorCode());
        stepFailedPayload.put("error", result.errorMessage() == null ? "" : result.errorMessage());
        eventPublisher.publish(event(task, ExecutionEventType.STEP_FAILED, stepFailedPayload));
        failTask(task, step.getId());
    }

    private void handleStepSuccess(TaskRun task, WorkflowDefinition workflow, StepRun stepRun,
                                   StepDefinition step, StepExecutionResult result, StepExecutionContext context) {
        if (result.output() != null) {
            store.saveArtifact(result.output());
            stepRun.setOutputArtifactId(result.output().artifactId());
            eventPublisher.publish(event(task, ExecutionEventType.ARTIFACT_CREATED,
                    Map.of("stepId", step.getId(),
                            "artifactId", result.output().artifactId(),
                            "artifactType", result.output().type().name(),
                            "outputChars", stringify(result.output().content()).length())));
        }
        stepRun.setStatus(StepStatus.SUCCEEDED);
        stepRun.setCompletedAt(Instant.now());
        store.saveStepRun(stepRun);
        Map<String, Object> stepSummaryPayload = new HashMap<>();
        stepSummaryPayload.put("stepId", step.getId());
        stepSummaryPayload.put("summary", summarize(result.output() != null ? result.output().content() : null));
        stepSummaryPayload.put("durationMs", stepRun.getDurationMs() == null ? 0L : stepRun.getDurationMs());
        stepSummaryPayload.put("attempt", stepRun.getAttempt());
        stepSummaryPayload.put("artifactId", result.output() != null ? result.output().artifactId() : "");
        eventPublisher.publish(event(task, ExecutionEventType.STEP_SUMMARY, stepSummaryPayload));

        Map<String, Object> stepCompletedPayload = stageMeta(workflow, step.getId(), step.getName());
        stepCompletedPayload.put("stepId", step.getId());
        stepCompletedPayload.put("status", "COMPLETED");
        eventPublisher.publish(event(task, ExecutionEventType.STEP_COMPLETED, stepCompletedPayload));

        scheduler.activateDownstream(task, workflow, stepRun, store);

        HumanInputDecision decision = humanInputPolicy().evaluate(task, context);
        if (decision.needInput()) {
            pauseTask(task, decision.reason(), step.getId());
        }
    }

    private void finishTask(TaskRun task, WorkflowDefinition workflow) {
        List<StepRun> runs = store.listStepRuns(task.getTaskRunId());
        boolean anyFailed = runs.stream().anyMatch(r -> r.getStatus() == StepStatus.FAILED);
        boolean allTerminal = !runs.isEmpty() && runs.stream().allMatch(r -> r.getStatus().isTerminal());
        if (anyFailed) {
            task.setStatus(TaskStatus.FAILED);
        } else if (allTerminal) {
            task.setStatus(TaskStatus.SUCCEEDED);
        } else {
            task.setStatus(TaskStatus.FAILED);
        }
        task.setCompletedAt(Instant.now());
        task.touch();
        store.updateTaskRun(task);
        if (task.getStatus() == TaskStatus.SUCCEEDED) {
            String finalText = resolveFinalText(task);
            String finalArtifactId = store.listArtifacts(task.getTaskRunId()).stream()
                    .filter(a -> a.type() == ArtifactType.FINAL_RESULT || a.type() == ArtifactType.FINAL_ARTICLE)
                    .map(Artifact::artifactId)
                    .findFirst()
                    .orElse("");
            eventPublisher.publish(event(task, ExecutionEventType.ARTIFACT_STARTED,
                    Map.of("artifactId", finalArtifactId, "artifactType", "markdown")));
            if (finalText != null && !finalText.isBlank()) {
                eventPublisher.publish(event(task, ExecutionEventType.ARTIFACT_DELTA,
                        Map.of("artifactId", finalArtifactId, "data", finalText)));
            }
            eventPublisher.publish(event(task, ExecutionEventType.ARTIFACT_COMPLETED,
                    Map.of("artifactId", finalArtifactId,
                            "artifactType", "markdown",
                            "contentLength", finalText == null ? 0 : finalText.length())));
            Map<String, Object> taskSummaryPayload = new HashMap<>();
            taskSummaryPayload.put("status", "COMPLETED");
            taskSummaryPayload.put("totalSteps", runs.size());
            taskSummaryPayload.put("durationMs", taskDuration(task));
            taskSummaryPayload.put("artifactId", finalArtifactId);
            // 任务总结：产物全文开头（供前端对话流展示总结，不流式输出全文）
            taskSummaryPayload.put("summary", finalText == null ? "" : summarize(finalText));
            eventPublisher.publish(event(task, ExecutionEventType.TASK_SUMMARY, taskSummaryPayload));
            Map<String, Object> taskCompletedPayload = new HashMap<>();
            taskCompletedPayload.put("status", "COMPLETED");
            taskCompletedPayload.put("artifactId", finalArtifactId);
            taskCompletedPayload.put("stageTotal", runs.size());
            taskCompletedPayload.put("percent", 100);
            eventPublisher.publish(event(task, ExecutionEventType.TASK_COMPLETED, taskCompletedPayload));
        } else {
            Map<String, Object> taskFailedPayload = new HashMap<>();
            taskFailedPayload.put("stageTotal", runs.size());
            taskFailedPayload.put("percent", 100);
            eventPublisher.publish(event(task, ExecutionEventType.TASK_FAILED, taskFailedPayload));
        }
    }

    private void failTask(TaskRun task, String stepId) {
        task.setStatus(TaskStatus.FAILED);
        task.setCompletedAt(Instant.now());
        task.touch();
        store.updateTaskRun(task);
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepId", stepId == null ? "" : stepId);
        List<StepRun> runs = store.listStepRuns(task.getTaskRunId());
        payload.put("stageTotal", runs.size());
        payload.put("percent", 100);
        eventPublisher.publish(event(task, ExecutionEventType.TASK_FAILED, payload));
    }

    private void pauseTask(TaskRun task, String reason, String stepId) {
        task.setStatus(TaskStatus.WAITING_INPUT);
        task.setWaitingInputReason(reason);
        task.touch();
        store.updateTaskRun(task);
        eventPublisher.publish(event(task, ExecutionEventType.TASK_WAITING_INPUT,
                Map.of("stepId", stepId == null ? "" : stepId,
                        "reason", reason == null ? "" : reason)));
    }

    private List<Artifact> loadInputs(TaskRun task, StepDefinition step) {
        List<ArtifactType> types = step.inputTypes();
        if (types.isEmpty()) {
            return List.of();
        }
        List<Artifact> all = store.listArtifacts(task.getTaskRunId());
        List<Artifact> inputs = new ArrayList<>();
        for (ArtifactType type : types) {
            for (Artifact artifact : all) {
                if (artifact.type() == type) {
                    inputs.add(artifact);
                    break;
                }
            }
        }
        return inputs;
    }

    private String resolveFinalText(TaskRun task) {
        List<Artifact> artifacts = store.listArtifacts(task.getTaskRunId());
        for (Artifact artifact : artifacts) {
            if (artifact.type() == ArtifactType.FINAL_RESULT || artifact.type() == ArtifactType.FINAL_ARTICLE) {
                return stringify(artifact.content());
            }
        }
        return null;
    }

    private String stringify(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Map<?, ?> map) {
            Object body = map.get("content") != null ? map.get("content")
                    : map.get("body") != null ? map.get("body") : map.get("text");
            if (body instanceof String bs) {
                return bs;
            }
            if (body != null) {
                return stringify(body);
            }
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            return String.valueOf(content);
        }
    }

    private ExecutionEvent event(TaskRun task, ExecutionEventType type, Map<String, Object> payload) {
        return ExecutionEvent.of(task.getTaskRunId(), task.getConversationId(), task.getTraceId(),
                sequence.incrementAndGet(), type, payload);
    }

    private Map<String, Object> planStep(StepDefinition step) {
        // [待办修复] 字段对齐前端 wsStore 期望（title/name/summary 三选一取真实步骤名），
        // 否则前端待办列表 fallback 为"执行步骤 N"占位名
        String stepName = step.getName() == null ? step.getId() : step.getName();
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", step.getId());
        m.put("stepId", step.getId());
        m.put("name", stepName);
        m.put("title", stepName);
        m.put("summary", stepName);
        m.put("stepName", stepName);
        m.put("executor", step.getExecutorType() != null ? step.getExecutorType().name() : "LLM");
        m.put("dependsOn", step.getDependsOn() != null ? step.getDependsOn() : java.util.List.of());
        m.put("status", "PENDING");
        return m;
    }

    /**
     * 构造步骤级进度元数据（对齐 docs/agent-frontend-rework.md §3.2）：
     * stageIndex（1-based）/ stageTotal / stageLabel / percent。
     */
    private Map<String, Object> stageMeta(WorkflowDefinition workflow, String stepId, String stepName) {
        Map<String, Object> meta = new HashMap<>();
        int total = workflow.getSteps().size();
        int index = stepIndex(workflow, stepId);
        meta.put("stageIndex", index >= 0 ? index + 1 : 0);
        meta.put("stageTotal", total);
        meta.put("stageLabel", stepName == null ? stepId : stepName);
        meta.put("percent", index >= 0 && total > 0 ? Math.max(1, (index + 1) * 100 / total) : 0);
        return meta;
    }

    private int stepIndex(WorkflowDefinition workflow, String stepId) {
        List<StepDefinition> steps = workflow.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getId().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 基于已完成步骤的平均耗时估算剩余时长（秒）。
     * 数据不足时返回 0，前端按 percent 自行展示，不阻塞链路。
     */
    private long estimateEtaSeconds(TaskRun task, WorkflowDefinition workflow) {
        List<StepRun> runs = store.listStepRuns(task.getTaskRunId());
        long doneCount = 0;
        long doneMs = 0;
        for (StepRun r : runs) {
            if (r.getStatus() == StepStatus.SUCCEEDED) {
                doneCount++;
                doneMs += r.getDurationMs() == null ? 0L : r.getDurationMs();
            }
        }
        long total = workflow.getSteps().size();
        long remaining = Math.max(0, total - doneCount);
        if (remaining == 0 || doneCount == 0 || doneMs == 0) {
            return 0;
        }
        return Math.max(1, (doneMs / doneCount * remaining) / 1000);
    }

    private String summarize(Object content) {
        String text = stringify(content).replaceAll("\\s+", " ").trim();
        if (text.isBlank()) return "Step completed";
        return text.length() > 240 ? text.substring(0, 240) + "…" : text;
    }

    private long taskDuration(TaskRun task) {
        if (task.getCreatedAt() == null || task.getCompletedAt() == null) return 0L;
        return Duration.between(task.getCreatedAt(), task.getCompletedAt()).toMillis();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private HumanInputPolicy humanInputPolicy() {
        HumanInputPolicy policy = humanInputPolicyProvider.getIfAvailable();
        return policy != null ? policy : PROCEED_POLICY;
    }
}
