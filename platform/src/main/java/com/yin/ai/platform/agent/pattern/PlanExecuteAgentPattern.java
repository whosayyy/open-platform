package com.yin.ai.platform.agent.pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.hitl.HitlCheckpoint;
import com.yin.ai.platform.agent.hitl.HitlPolicy;
import com.yin.ai.platform.agent.hitl.HitlStore;
import com.yin.ai.platform.agent.pattern.context.ContextManager;
import com.yin.ai.platform.agent.pattern.context.InputBinding;
import com.yin.ai.platform.agent.pattern.context.ResolutionMode;
import com.yin.ai.platform.agent.pattern.context.StepContract;
import com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher;
import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.executor.ParallelStepExecutor;
import com.yin.ai.platform.agent.pattern.reflect.ReflectDecision;
import com.yin.ai.platform.agent.pattern.reflect.ReflectResult;
import com.yin.ai.platform.agent.pattern.reflect.StepReflector;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.workflow.load.WorkflowRegistry;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import com.yin.ai.platform.gateway.ModelExecutionOptions;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.structured.StructuredClient;
import com.yin.ai.platform.core.rag.grounding.ClaimCitationAnalyzer;
import com.yin.ai.platform.rag.grounding.ClaimEvidenceQualityJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAN_EXECUTE 模式 — 企业级规划-执行引擎。
 * <p>
 * 与骨架版的核心差异：
 * <ul>
 *   <li>受约束规划：Planner 知道系统有哪些可用 executor，不会规划出不存在的步骤</li>
 *   <li>契约化执行：每步通过 ContextManager 解析输入绑定，结构化传递数据</li>
 *   <li>步骤反思：每步完成后 Reflector 评估质量，可触发重试/重规划</li>
 *   <li>执行器路由：根据 step.executor 路由到 LLM/Skill/Tool，不是全部调 LLM</li>
 *   <li>计划校验：执行前验证 executor 可用性、DAG 合法性</li>
 * </ul>
 * <p>
 * 执行流程：
 * <pre>
 * ① PLANNING：受约束规划 + 计划校验
 * ② [可选] HITL 审批门（高风险工具执行前）
 * ③ EXECUTION：契约化逐步执行 + Reflector 反思
 * ④ SUMMARY：汇总产物
 * </pre>
 */
public class PlanExecuteAgentPattern implements AgentPattern {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgentPattern.class);
    private static final int MAX_RETRIES_PER_STEP = 2;
    private static final int MAX_REPLAN_COUNT = 3;
    private static final int MAX_REFLECTION_RETRIES = 2;
    private static final double MIN_FINAL_CLAIM_CITATION_COVERAGE = 0.90;
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s<>()]+");

    private final GatewayTextClient chatClient;
    private final StructuredClient structuredClient;
    private final StepExecutorDispatcher dispatcher;
    private final ContextManager contextManager;
    private final StepReflector reflector;
    private final ParallelStepExecutor parallelStepExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultStepSummaryGenerator stepSummaryGenerator = new DefaultStepSummaryGenerator();
    private HitlPolicy hitlPolicy;
    private HitlStore hitlStore;
    /** [兜底] workflow 注册表（可选注入；LLM 规划失败时用静态 workflow 计划兜底） */
    private WorkflowRegistry workflowRegistry;
    private ClaimEvidenceQualityJudge claimEvidenceQualityJudge;

    public PlanExecuteAgentPattern(GatewayTextClient chatClient,
                                    StructuredClient structuredClient,
                                    StepExecutorDispatcher dispatcher,
                                    ContextManager contextManager,
                                    StepReflector reflector) {
        this(chatClient, structuredClient, dispatcher, contextManager, reflector, null);
    }

    public PlanExecuteAgentPattern(GatewayTextClient chatClient,
                                   StructuredClient structuredClient,
                                   StepExecutorDispatcher dispatcher,
                                   ContextManager contextManager,
                                   StepReflector reflector,
                                   ParallelStepExecutor parallelStepExecutor) {
        this.chatClient = chatClient;
        this.structuredClient = structuredClient;
        this.dispatcher = dispatcher;
        this.contextManager = contextManager;
        this.reflector = reflector;
        this.parallelStepExecutor = parallelStepExecutor;
    }

    /** [兜底] 注入 workflow 注册表（LLM 规划失败时按任务类型回退到静态 workflow 计划）。 */
    public void setWorkflowRegistry(WorkflowRegistry workflowRegistry) {
        this.workflowRegistry = workflowRegistry;
    }

    public void setApprovalGate(HitlPolicy hitlPolicy, HitlStore hitlStore) {
        this.hitlPolicy = hitlPolicy;
        this.hitlStore = hitlStore;
    }

    public void setClaimEvidenceQualityJudge(ClaimEvidenceQualityJudge judge) {
        this.claimEvidenceQualityJudge = judge;
    }

    @Override
    public AgentMode mode() {
        return AgentMode.PLAN_EXECUTE;
    }

    @Override
    public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
        return Flux.create(sink -> {
            String taskId = ctx.getTaskId();
            EventPublisher publisher = ctx.getEventPublisher();
            EventPublisher delegate = publisher;
            ctx.setEventPublisher(new EventPublisher() {
                @Override
                public void publish(AgentEvent event) {
                    delegate.publish(event);
                    sink.next(event);
                }

                @Override
                public reactor.core.publisher.Flux<AgentEvent> replay(String replayTaskId, String lastEventId) {
                    return delegate.replay(replayTaskId, lastEventId);
                }
            });

            try {
                // === Phase 1: Constrained Planning ===
                boolean resuming = Boolean.TRUE.equals(ctx.getVariable("resume"))
                        && ctx.getPlan() != null && !ctx.getPlan().steps().isEmpty();
                TaskPlan plan;
                if (resuming) {
                    plan = resetInterruptedSteps(ctx.getPlan());
                    // Preserve completed steps/artifacts and only reset the interrupted
                    // RUNNING/FAILED step. Publish plan.updated(restored=true), not a
                    // second plan.created event.
                    ctx.replacePlan(plan, true);
                    if (ctx.getState() == TaskState.PLANNING || ctx.getState() == TaskState.FAILED
                            || ctx.getState() == TaskState.WAITING) {
                        ctx.transitionTo(TaskState.RUNNING);
                    }
                    emit(sink, publisher, AgentEvent.progress(taskId,
                            (int) plan.completedCount(), plan.totalSteps(), "从断点继续执行..."));
                } else {
                    ctx.transitionTo(TaskState.PLANNING);
                    emit(sink, publisher, AgentEvent.taskPlanning(taskId));
                    emit(sink, publisher, AgentEvent.progress(taskId, 0, 0, "正在生成执行计划..."));

                    ModelExecutionOptions planningReasoning =
                            ReasoningOptionsSupport.resolve(ctx, StepType.PLANNING);
                    emit(sink, publisher,
                            AgentEvent.reasoningStarted(taskId, "planning", planningReasoning));
                    plan = createConstrainedPlan(ctx);
                    emit(sink, publisher,
                            AgentEvent.reasoningCompleted(taskId, "planning", planningReasoning, 0));

                    // 计划校验
                    String validationError = validatePlan(plan);
                    if (validationError != null) {
                        log.warn("[PLAN_EXECUTE] plan validation failed: {}", validationError);
                        // 校验失败时 fallback 到单步 LLM 执行
                        plan = fallbackPlan(ctx.getGoal());
                    }

                    ctx.setPlan(plan);
                    ctx.transitionTo(TaskState.RUNNING);
                }

                log.info("[PLAN_EXECUTE] taskId={} plan created: {} steps", taskId, plan.totalSteps());
                emit(sink, publisher, AgentEvent.planSummary(
                        taskId, ctx.getUserVisiblePlan(), resuming));
                emit(sink, publisher, AgentEvent.taskStarted(taskId));

                // === Phase 2: Contract-based Execution with Reflection ===
                int replanCount = 0;
                Map<String, Integer> retryCounts = new HashMap<>();
                Map<String, String> pendingRetryHints = new HashMap<>();
                int total = ctx.getPlan().totalSteps();

                // V2 owns scheduling in this loop. Parallel workers must enter through
                // the same execution-lease/result/summary pipeline before being enabled.

                for (int i = 0; i < ctx.getPlan().totalSteps(); i++) {
                    TaskStep step = ctx.getPlan().steps().get(i);
                    if (step.status() != StepStatus.PENDING) continue;

                    applyQueuedInstructions(ctx);

                    List<TaskStep> dependencies = step.dependsOn().stream()
                            .map(dependencyId -> ctx.getPlan().steps().stream()
                                    .filter(candidate -> candidate.id().equals(dependencyId))
                                    .findFirst())
                            .flatMap(Optional::stream)
                            .toList();
                    if (dependencies.stream().anyMatch(dependency ->
                            dependency.status() == StepStatus.FAILED
                                    || dependency.status() == StepStatus.SKIPPED)) {
                        ctx.updateStep(step.id(), step.withStatus(StepStatus.SKIPPED));
                        saveCheckpoint(ctx);
                        emit(sink, publisher, AgentEvent.stepSkipped(taskId, step.id(),
                                "dependency failed or was rejected"));
                        continue;
                    }
                    if (dependencies.stream().anyMatch(dependency ->
                            dependency.status() != StepStatus.COMPLETED)) {
                        throw new IllegalStateException("No dependency-ready step for " + step.id());
                    }

                    ApprovalGate approvalGate = approvalGate(ctx, step, i, sink, publisher);
                    if (approvalGate == ApprovalGate.PAUSE) {
                        sink.complete();
                        return;
                    }
                    if (approvalGate == ApprovalGate.SKIP) continue;

                    int attempt = retryCounts.getOrDefault(step.id(), 0) + 1;
                    StepExecution execution = StepExecution.started(taskId, step.id(), attempt);
                    if (!ctx.tryAcquireStepExecution(execution, java.time.Duration.ofMinutes(30))) {
                        throw new IllegalStateException("Duplicate step execution rejected: "
                                + execution.executionId());
                    }

                    step = step.withStatus(StepStatus.RUNNING);
                    ctx.updateStep(step.id(), step);
                    ctx.setCurrentStep(step.id(), i);
                    saveCheckpoint(ctx);
                    emit(sink, publisher, AgentEvent.stepStarted(taskId, step.id(), step.description()));
                    emit(sink, publisher, AgentEvent.stepProgress(taskId, step.id(), i + 1,
                            ctx.getPlan().totalSteps(), step.description()));
                    boolean toolExecutor = isToolExecutor(step.executor());
                    if (toolExecutor) {
                        emit(sink, publisher, AgentEvent.toolStarted(
                                taskId, step.id(), step.executor(), step.description()));
                    }

                    // 契约化执行（含重试）
                    ModelExecutionOptions reasoning = resolveReasoning(ctx, step);
                    ctx.putVariable("modelExecutionOptions", reasoning);
                    emit(sink, publisher, AgentEvent.reasoningStarted(taskId, step.id(), reasoning));
                    StepExecutorResult result = executeStepWithContract(step, ctx, pendingRetryHints);
                    emit(sink, publisher, AgentEvent.reasoningCompleted(
                            taskId, step.id(), reasoning, result.tokensUsed()));

                    if (result.success()) {
                        ctx.updateStep(step.id(), step.withOutput(result.output()));
                        ctx.putVariable("step." + step.id() + ".output", result.output());
                        StepArtifactStore.ArtifactRef artifactRef =
                                ctx.saveStepArtifact(step.id(), i, result.output());
                        // 【BUG 修复】把产物 objectKey 写入 artifact.{stepId}.key 变量——
                        // ContextManager.resolveSummary 的 view_artifact 分支（binding.key="artifactKey"）
                        // 读的就是这个变量；原来从不写入 → view_artifact 步骤总是 no ref found 失败。
                        if (artifactRef != null) {
                            ctx.putVariable("artifact." + step.id() + ".key", artifactRef.objectKey());
                            if (artifactRef.url() != null) {
                                ctx.putVariable("artifact." + step.id() + ".url", artifactRef.url());
                            }
                        }
                        String preliminarySummary = summarizeStep(result.output(), artifactRef);
                        int contextVersion = updateDynamicContext(ctx, step, preliminarySummary, artifactRef);
                        StepResult structuredResult = createStepResult(execution, step, result,
                                artifactRef, contextVersion);
                        String nextAction = i + 1 < ctx.getPlan().steps().size()
                                ? ctx.getPlan().steps().get(i + 1).description() : "汇总并交付最终产物";
                        long summaryStartedAt = System.nanoTime();
                        StepSummary stepSummary = generateStreamingStepSummary(
                                ctx, step, structuredResult, result.output(), nextAction, sink, publisher);
                        io.micrometer.core.instrument.Metrics.timer(
                                        "ai.agent.step.summary.duration", "stepType",
                                        (step.stepType() != null ? step.stepType()
                                                : StepType.infer(step.executor(), step.description())).name())
                                .record(System.nanoTime() - summaryStartedAt,
                                        java.util.concurrent.TimeUnit.NANOSECONDS);
                        ctx.recordStepResult(structuredResult);
                        ctx.recordStepSummary(stepSummary);
                        persistExecution(ctx, execution.complete(StepStatus.COMPLETED), structuredResult);
                        saveCheckpoint(ctx);
                        if (step.executor() != null && step.executor().startsWith("web_search")) {
                            List<Map<String, Object>> references = parseSearchReferences(result.output());
                            if (!references.isEmpty()) {
                                emit(sink, publisher, AgentEvent.referencesFound(taskId, step.id(), references));
                            }
                        }
                        if (toolExecutor) {
                            emit(sink, publisher, AgentEvent.toolCompleted(
                                    taskId, step.id(), step.executor(), step.description()));
                        }
                        emit(sink, publisher, AgentEvent.contextUpdated(taskId, step.id(), contextVersion,
                                stepSummary.content(),
                                artifactRef != null ? artifactRef.objectKey() : null,
                                artifactRef != null ? artifactRef.url() : null,
                                result.output() == null ? 0 : result.output().length()));
                        emit(sink, publisher, AgentEvent.stepResult(taskId, structuredResult));
                        emit(sink, publisher, AgentEvent.stepSummary(taskId, stepSummary));
                        // The quality reflector must accept the attempt before it
                        // can become a user-reviewable milestone.
                        ReflectResult reflection = reflector.reflect(step, result.output(), ctx);
                        ReflectDecision action = handleReflection(reflection, ctx, step);
                        boolean publishIntermediate = action != ReflectDecision.RETRY
                                && action != ReflectDecision.ABORT
                                && shouldPublishIntermediateArtifact(ctx, step, result.output());
                        emit(sink, publisher, AgentEvent.stepCompleted(
                                taskId, step.id(), result.output(), publishIntermediate));
                        pendingRetryHints.remove(step.id());

                        if (action == ReflectDecision.REPLAN && replanCount < MAX_REPLAN_COUNT) {
                            replanCount++;
                            i = -1;  // 重置循环，重新扫描 pending 步骤
                            total = ctx.getPlan().totalSteps();
                        } else if (action == ReflectDecision.RETRY) {
                            int retries = retryCounts.merge(step.id(), 1, Integer::sum);
                            if (retries <= MAX_REFLECTION_RETRIES) {
                                pendingRetryHints.put(step.id(),
                                        reflection.retryHint() == null ? "" : reflection.retryHint());
                                log.warn("[PLAN_EXECUTE] step={} reflection RETRY ({}/{})",
                                        step.id(), retries, MAX_REFLECTION_RETRIES);
                                i = -1;  // 重新扫描，重试该步骤
                                total = ctx.getPlan().totalSteps();
                            } else {
                                log.warn("[PLAN_EXECUTE] step={} retry budget exhausted, accepting output",
                                        step.id());
                                ctx.updateStep(step.id(), step.withOutput(result.output()));
                                pendingRetryHints.remove(step.id());
                            }
                        } else if (action == ReflectDecision.ABORT) {
                            emit(sink, publisher, AgentEvent.taskFailed(taskId, reflection.reason()));
                            sink.error(new RuntimeException("Aborted: " + reflection.reason()));
                            return;
                        }
                    } else {
                        ctx.updateStep(step.id(), step.withError(result.error()));
                        StepResult structuredResult = createStepResult(execution, step, result, null,
                                ctx.getVariable("context.version") instanceof Number n ? n.intValue() : 0);
                        ctx.recordStepResult(structuredResult);
                        persistExecution(ctx, execution.complete(StepStatus.FAILED), structuredResult);
                        saveCheckpoint(ctx);
                        emit(sink, publisher, AgentEvent.stepResult(taskId, structuredResult));
                        if (toolExecutor) {
                            emit(sink, publisher, AgentEvent.toolFailed(
                                    taskId, step.id(), step.executor(), step.description(), result.error()));
                        }
                        emit(sink, publisher, AgentEvent.stepFailed(taskId, step.id(), result.error()));
                        log.warn("[PLAN_EXECUTE] step failed: {} - {}", step.id(), result.error());
                    }
                }

                // === Phase 3: Summary ===
                List<TaskStep> unresolved = ctx.getPlan().steps().stream()
                        .filter(step -> step.status() == StepStatus.FAILED
                                || step.status() == StepStatus.RUNNING
                                || step.status() == StepStatus.PENDING)
                        .toList();
                if (!unresolved.isEmpty()) {
                    throw new IllegalStateException("Plan contains unresolved steps: "
                            + unresolved.stream().map(TaskStep::id).toList());
                }
                String finalOutput = ensureValidFinalArtifact(ctx, summarize(ctx));
                ctx.putVariable("finalOutput", finalOutput);
                StepArtifactStore.ArtifactRef finalRef =
                        ctx.saveStepArtifact("result", ctx.getPlan().totalSteps(), finalOutput);
                if (finalRef == null) {
                    throw new IllegalStateException("最终产物未写入 Artifact Store，任务不能标记为完成");
                }
                StepArtifactStore.Verification verification = ctx.verifyStepArtifact(finalRef);
                if (verification == null || !verification.verified()) {
                    throw new IllegalStateException("最终产物写入后校验失败，任务不能标记为完成: "
                            + (verification != null ? verification.error() : "verification unavailable"));
                }
                ctx.putVariable("finalArtifactKey", finalRef.objectKey());
                if (finalRef.url() != null) {
                    ctx.putVariable("finalArtifactUrl", finalRef.url());
                }
                if (finalRef.name() != null) {
                    ctx.putVariable("finalArtifactName", finalRef.name());
                }
                if (verification.sha256() != null) {
                    ctx.putVariable("finalArtifactSha256", verification.sha256());
                }
                ctx.putVariable("finalArtifactSize", verification.size());
                ctx.putVariable("finalArtifactVerified", Boolean.TRUE);
                ctx.addArtifact(Artifact.of("result.md", "markdown", finalOutput, "summary"));
                List<String> finalArtifacts = finalRef.objectKey() == null
                        ? List.of() : List.of(finalRef.objectKey());
                TaskSummary taskSummary = new TaskSummary(taskId, "任务已完成",
                        "任务已经完成，最终产物已生成并通过交付检查。",
                        completedFindings(ctx), finalArtifacts,
                        "请前往右侧「产物」区域查看最终产物；如需调整，可继续告诉我修改要求。");
                ctx.putVariable("taskSummary", taskSummary);
                emit(sink, publisher, AgentEvent.taskSummary(taskId, taskSummary));
                emit(sink, publisher, AgentEvent.progress(taskId, total, total, "任务完成"));

                sink.complete();

            } catch (Exception e) {
                log.error("[PLAN_EXECUTE] taskId={} failed", taskId, e);
                emit(sink, publisher, AgentEvent.taskFailed(taskId, e.getMessage()));
                sink.error(e);
            }
        });
    }

    private void emit(FluxSink<AgentEvent> sink, EventPublisher publisher, AgentEvent event) {
        publisher.publish(event);
        sink.next(event);
    }

    private TaskPlan resetInterruptedSteps(TaskPlan plan) {
        List<TaskStep> steps = plan.steps().stream()
                .map(step -> step.status() == StepStatus.FAILED || step.status() == StepStatus.RUNNING
                        ? step.withStatus(StepStatus.PENDING)
                        : step)
                .toList();
        return new TaskPlan(plan.goal(), steps);
    }

    private void saveCheckpoint(AgentExecutionContext ctx) {
        ctx.getContextStore().saveSnapshot(ctx.getTaskId(), ctx.snapshot());
    }

    private ApprovalGate approvalGate(AgentExecutionContext ctx, TaskStep step, int stepIndex,
                                      FluxSink<AgentEvent> sink, EventPublisher publisher) {
        if (hitlPolicy == null || hitlStore == null) return ApprovalGate.EXECUTE;
        String pendingStep = stringValue(ctx.getVariable("approval.pending.stepId"));
        if (step.id().equals(pendingStep)) {
            String decision = stringValue(ctx.getVariable("approvalDecision"));
            if (decision == null || decision.isBlank()) return ApprovalGate.PAUSE;
            String approvalId = stringValue(ctx.getVariable("approval.pending.id"));
            String resolvedBy = stringValue(ctx.getVariable("approvalResolvedBy"));
            emit(sink, publisher, AgentEvent.approvalResolved(ctx.getTaskId(), step.id(),
                    approvalId == null ? "" : approvalId, decision,
                    resolvedBy == null ? "user" : resolvedBy));
            ctx.putVariable("approval.pending.stepId", "");
            ctx.putVariable("approval.pending.id", "");
            ctx.putVariable("approvalDecision", "");
            if ("reject".equalsIgnoreCase(decision)) {
                ctx.updateStep(step.id(), step.withStatus(StepStatus.SKIPPED));
                saveCheckpoint(ctx);
                emit(sink, publisher, AgentEvent.stepSkipped(
                        ctx.getTaskId(), step.id(), "approval rejected"));
                return ApprovalGate.SKIP;
            }
            return ApprovalGate.EXECUTE;
        }

        HitlCheckpoint checkpoint = hitlPolicy.evaluate(step, ctx.getTaskId(), stepIndex);
        if (checkpoint == null) return ApprovalGate.EXECUTE;
        hitlStore.save(checkpoint);
        ctx.putVariable("approval.pending.stepId", step.id());
        ctx.putVariable("approval.pending.id", checkpoint.approvalId());
        ctx.transitionTo(TaskState.WAITING);
        saveCheckpoint(ctx);
        emit(sink, publisher, AgentEvent.approvalRequired(ctx.getTaskId(), step.id(),
                checkpoint.approvalId(), checkpoint.action(), checkpoint.riskLevel().name()));
        emit(sink, publisher, AgentEvent.taskWaiting(
                ctx.getTaskId(), "approval", checkpoint.approvalId()));
        return ApprovalGate.PAUSE;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private enum ApprovalGate { EXECUTE, SKIP, PAUSE }

    private StepResult createStepResult(StepExecution execution, TaskStep step,
                                        StepExecutorResult result,
                                        StepArtifactStore.ArtifactRef artifactRef,
                                        int contextVersion) {
        Map<String, Object> value = structuredValue(result.output());
        String resultRef = artifactRef != null ? artifactRef.objectKey() : null;
        List<String> artifactRefs = resultRef == null ? List.of() : List.of(resultRef);
        List<String> evidenceRefs = step.stepType() == StepType.SEARCH
                ? parseSearchReferences(result.output()).stream()
                    .map(item -> String.valueOf(item.getOrDefault("url", "")))
                    .filter(url -> !url.isBlank()).toList()
                : List.of();
        return new StepResult(execution.executionId(), step.id(), result.success(), value,
                resultRef, artifactRefs, evidenceRefs,
                contextVersion > 0 ? "context:v" + contextVersion : null,
                result.success() ? null : result.error(), java.time.Instant.now());
    }

    private ModelExecutionOptions resolveReasoning(AgentExecutionContext ctx, TaskStep step) {
        return ReasoningOptionsSupport.resolve(ctx, step.stepType());
    }

    private void applyQueuedInstructions(AgentExecutionContext ctx) {
        if (ctx.getTaskEngine() == null) return;
        List<String> instructions = ctx.getTaskEngine().drainInstructions(ctx.getTaskId());
        if (instructions.isEmpty()) return;
        String current = contextPack(ctx);
        String addition = "[User instructions received during execution]\n- "
                + String.join("\n- ", instructions);
        String merged = current.isBlank() ? addition : current + "\n\n" + addition;
        if (merged.length() > 16000) merged = merged.substring(merged.length() - 16000);
        ctx.putVariable("sessionContext", merged);
    }

    private Map<String, Object> structuredValue(String output) {
        if (output == null || output.isBlank()) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(output);
            if (node.isObject()) {
                return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            }
        } catch (Exception ignored) { }
        if (output.length() <= 2000) return Map.of("text", output, "contentType", "text/plain");
        return Map.of("textPreview", output.substring(0, 1000) + "…",
                "contentType", "text/plain", "characters", output.length());
    }

    private void persistExecution(AgentExecutionContext ctx, StepExecution execution, StepResult result) {
        if (ctx.getTaskEngine() == null) return;
        ctx.getTaskEngine().saveStepResult(ctx.getTaskId(), execution.stepId(),
                result.success()
                        ? com.yin.ai.platform.agent.task.TaskStepResult.success(execution.stepId(),
                            result.resultRef() != null ? result.resultRef() : result.value().toString(),
                            0, execution.durationMs())
                        : com.yin.ai.platform.agent.task.TaskStepResult.failure(execution.stepId(),
                            result.error(), execution.durationMs()));
        ctx.getTaskEngine().saveStepExecutionRecord(ctx.getTaskId(),
                new com.yin.ai.platform.agent.task.StepExecutionRecord(execution.stepId(), execution.attempt(),
                        execution.completedAt(), execution.status(), result.resultRef(), result.error(), false));
    }

    private String stepSummaryContent(AgentExecutionContext ctx) {
        if (ctx.getPlan() == null) return "";
        return ctx.getPlan().steps().stream()
                .map(step -> ctx.getVariable("step." + step.id() + ".summary"))
                .filter(StepSummary.class::isInstance).map(StepSummary.class::cast)
                .map(StepSummary::content).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private List<String> completedFindings(AgentExecutionContext ctx) {
        if (ctx.getPlan() == null) return List.of();
        return ctx.getPlan().steps().stream()
                .map(step -> ctx.getVariable("step." + step.id() + ".summary"))
                .filter(StepSummary.class::isInstance).map(StepSummary.class::cast)
                .flatMap(summary -> summary.findings().stream()).limit(8).toList();
    }

    @Override
    public boolean resumable() {
        return true;
    }

    @Override
    public int priority() {
        return 100;
    }

    // === 受约束规划 ===

    private TaskPlan createConstrainedPlan(AgentExecutionContext ctx) {
        String goal = ctx.getGoal();
        String availableExecutors = buildExecutorDescription();
        String sessionContext = contextPack(ctx);

        // 工具调用式规划（opt-in）：模型按 submitPlan 工具的 schema 返回计划，结构由模型层保证
        if (PlanToolGenerator.enabled()) {
            String toolPrompt = buildPlanningPrompt(goal, availableExecutors, sessionContext, true);
            String systemPrompt = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
            Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
            Optional<TaskPlan> viaTool = PlanToolGenerator.generate(structuredClient, goal, toolPrompt,
                    systemPrompt, temp, executor -> dispatcher.isAvailable(executor));
            if (viaTool.isPresent()) {
                log.info("[PLAN_EXECUTE] taskId={} plan via tool-call: {} steps",
                        ctx.getTaskId(), viaTool.get().totalSteps());
                return constrainPlan(ctx, ensureFreshSearchStep(ctx, viaTool.get()));
            }
            log.warn("[PLAN_EXECUTE] tool-based planning failed, falling back to prompt+JSON");
        }

        String prompt = buildPlanningPrompt(goal, availableExecutors, sessionContext, false);
        try {
            String response = callWithConfig(ctx, prompt);
            TaskPlan parsed = parsePlan(response, goal);
            if (parsed != null) return constrainPlan(ctx, ensureFreshSearchStep(ctx, parsed));
        } catch (Exception e) {
            log.warn("[PLAN_EXECUTE] LLM planning failed: {}", e.getMessage());
        }

        // LLM planning failure may fall back to an application-selected static workflow.
        TaskPlan workflowPlan = workflowFallbackPlan(ctx);
        if (workflowPlan != null && !workflowPlan.steps().isEmpty()) {
            return constrainPlan(ctx, ensureFreshSearchStep(ctx, workflowPlan));
        }

        return constrainPlan(ctx, ensureFreshSearchStep(ctx, fallbackPlan(goal)));
    }

    /**
     * 从 WorkflowRegistry 取应用通过 workflowName 显式选择的静态 workflow，转成 TaskPlan。
     * 无可用 workflow 返回 null（由调用方继续降级）。
     */
    private TaskPlan workflowFallbackPlan(AgentExecutionContext ctx) {
        if (workflowRegistry == null) {
            return null;
        }
        Object explicit = ctx.getVariable("workflowName");
        String workflowName = explicit instanceof String s && !s.isBlank() ? s : null;
        if (workflowName == null) {
            return null;
        }
        java.util.Optional<WorkflowDefinition> wfOpt = workflowRegistry.find(workflowName);
        if (wfOpt.isEmpty()) {
            log.warn("[PLAN_EXECUTE] workflow '{}' not found, skip fallback", workflowName);
            return null;
        }
        List<TaskStep> steps = new ArrayList<>();
        for (StepDefinition step : wfOpt.get().getSteps()) {
            String id = step.getId() != null ? step.getId() : "step-" + steps.size();
            String desc = step.getName() != null ? step.getName() : id;
            String executor = resolveWorkflowExecutor(step);
            List<String> deps = step.getDependsOn() != null ? step.getDependsOn() : List.of();
            steps.add(TaskStep.pending(id, desc, executor, deps));
        }
        if (steps.isEmpty()) {
            return null;
        }
        log.info("[PLAN_EXECUTE] taskId={} LLM planning failed → workflow fallback '{}' ({} steps)",
                ctx.getTaskId(), workflowName, steps.size());
        return new TaskPlan(ctx.getGoal(), steps);
    }

    /** workflow 执行器类型 → TaskStep executor 名（研究步骤优先用可用搜索工具） */
    private String resolveWorkflowExecutor(StepDefinition step) {
        com.yin.ai.platform.workflow.model.ExecutorType type = step.getExecutorType();
        if (type == com.yin.ai.platform.workflow.model.ExecutorType.AGENT) {
            return dispatcher.isAvailable("web_search") ? "web_search" : "llm";
        }
        return "llm";
    }

    /** Apply deterministic product constraints after the LLM planner returns. */
    private TaskPlan constrainPlan(AgentExecutionContext ctx, TaskPlan plan) {
        TaskPlan artifactPlan = ensureFinalArtifactPlan(ctx, plan);
        return ensureArtifactReadback(ctx, artifactPlan);
    }

    /** Keep intermediate work private and make an application-requested final artifact explicit. */
    private TaskPlan ensureFinalArtifactPlan(AgentExecutionContext ctx, TaskPlan plan) {
        if (plan == null || !artifactRequired(ctx) || plan.steps().isEmpty()) {
            return plan;
        }

        long meaningfulDescriptions = plan.steps().stream()
                .map(TaskStep::description)
                .filter(java.util.Objects::nonNull)
                .map(this::normalizeDescription)
                .distinct()
                .count();
        // 规则兜底：仅当 LLM 规划明显退化（步骤多但描述几乎雷同）时才替换为粗粒度模板；
        // 正常 LLM 规划（即使步骤少）优先保留
        boolean degenerate = plan.steps().size() >= 5 && meaningfulDescriptions <= 2;
        boolean hasFinalArtifact = plan.steps().stream().anyMatch(step -> isFinalArtifactStep(ctx, step));

        if (degenerate) {
            log.info("[PLAN_EXECUTE] replacing degenerate artifact plan taskId={}", ctx.getTaskId());
            return deterministicArtifactPlan(ctx, plan.goal());
        }
        if (hasFinalArtifact) {
            return plan;
        }

        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (TaskStep step : plan.steps()) {
            if (step.dependsOn() != null) referenced.addAll(step.dependsOn());
        }
        List<String> leaves = plan.steps().stream()
                .map(TaskStep::id)
                .filter(id -> !referenced.contains(id))
                .toList();
        List<String> dependencies = leaves.isEmpty()
                ? List.of(plan.steps().get(plan.steps().size() - 1).id())
                : leaves;
        String finalId = finalArtifactStepId(ctx);
        int suffix = 2;
        boolean idTaken;
        do {
            idTaken = false;
            for (TaskStep existing : plan.steps()) {
                if (finalId.equals(existing.id())) {
                    idTaken = true;
                    finalId = finalArtifactStepId(ctx) + "-" + suffix++;
                    break;
                }
            }
        } while (idTaken);
        List<TaskStep> steps = new ArrayList<>(plan.steps());
        steps.add(TaskStep.pending(finalId,
                stringVariable(ctx, "finalStepDescription", "合成最终产物"), "llm", dependencies));
        return new TaskPlan(plan.goal(), steps);
    }

    /** Generic fallback used only when an application-requested artifact plan degenerates. */
    private TaskPlan deterministicArtifactPlan(AgentExecutionContext ctx, String goal) {
        boolean fresh = contextPack(ctx).contains("[Freshness policy]");
        String researchExecutor = fresh && dispatcher.isAvailable("web_search") ? "web_search" : "llm";
        return new TaskPlan(goal, List.of(
                TaskStep.pending("collect-inputs", "收集并验证输入", researchExecutor, List.of()),
                TaskStep.pending("candidate-artifact", "生成候选产物", "llm", List.of("collect-inputs")),
                TaskStep.pending(finalArtifactStepId(ctx),
                        stringVariable(ctx, "finalStepDescription", "合成最终产物"),
                        "llm", List.of("candidate-artifact"))
        ));
    }

    private boolean isFinalArtifactStep(AgentExecutionContext ctx, TaskStep step) {
        if (step == null || !"llm".equalsIgnoreCase(step.executor())) return false;
        return finalArtifactStepId(ctx).equals(step.id());
    }

    private boolean artifactRequired(AgentExecutionContext ctx) {
        Object value = ctx.getVariable("artifactRequired");
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String finalArtifactStepId(AgentExecutionContext ctx) {
        return stringVariable(ctx, "finalStepId", "final-artifact");
    }

    private String stringVariable(AgentExecutionContext ctx, String key, String fallback) {
        Object value = ctx.getVariable(key);
        return value != null && !value.toString().isBlank() ? value.toString() : fallback;
    }

    private String normalizeDescription(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private TaskPlan ensureFreshSearchStep(AgentExecutionContext ctx, TaskPlan plan) {
        String sessionContext = contextPack(ctx);
        boolean required = sessionContext.contains("[Freshness policy]");
        if (!required || !dispatcher.isAvailable("web_search") || plan == null || plan.steps().isEmpty()
                || plan.steps().stream().anyMatch(step -> step.executor() != null && step.executor().startsWith("web_search"))) {
            return plan;
        }

        String searchId = "fresh-search";
        List<TaskStep> steps = new ArrayList<>();
        steps.add(TaskStep.pending(searchId, "先联网搜索当前事实与权威来源", "web_search", List.of()));
        for (TaskStep step : plan.steps()) {
            List<String> dependencies = step.dependsOn() == null ? List.of() : step.dependsOn();
            if (dependencies.isEmpty()) dependencies = List.of(searchId);
            steps.add(new TaskStep(step.id(), step.description(), step.executor(), dependencies,
                    step.status(), step.output(), step.error(), step.requiresMemory(), step.reason()));
        }
        log.info("[PLAN_EXECUTE] inserted mandatory freshness search taskId={}", ctx.getTaskId());
        return new TaskPlan(plan.goal(), steps);
    }

    /**
     * Artifact plans must give the final synthesizer the full previous output.
     * Insert a readback step before the final LLM step
     * when MinIO-backed view_artifact is available and the model omitted it.
     */
    private TaskPlan ensureArtifactReadback(AgentExecutionContext ctx, TaskPlan plan) {
        if (plan == null || plan.steps().size() < 2
                || !dispatcher.isAvailable("view_artifact")
                || !artifactRequired(ctx)) {
            return plan;
        }
        TaskStep last = plan.steps().get(plan.steps().size() - 1);
        if (!"llm".equals(last.executor())
                || last.dependsOn() == null || last.dependsOn().isEmpty()
                || plan.steps().stream().anyMatch(s -> "view_artifact".equals(s.executor()))) {
            return plan;
        }

        String viewId = last.id() + "-artifact";
        TaskStep view = TaskStep.pending(viewId,
                "读取完整候选产物", "view_artifact", last.dependsOn());
        TaskStep finalStep = new TaskStep(last.id(), last.description(), last.executor(),
                List.of(viewId), last.status(), last.output(), last.error(), last.requiresMemory(), last.reason());
        List<TaskStep> steps = new ArrayList<>(plan.steps());
        steps.set(steps.size() - 1, view);
        steps.add(finalStep);
        log.info("[PLAN_EXECUTE] inserted artifact readback before final step={}, taskId={}",
                last.id(), ctx.getTaskId());
        return new TaskPlan(plan.goal(), steps);
    }

    private String buildPlanningPrompt(String goal, String availableExecutors,
                                       String sessionContext, boolean toolMode) {
        String formatRule = toolMode
                ? "5. 调用 submitPlan 工具提交计划（工具参数即为计划，不要输出任何文字）\n"
                : "5. 只输出 JSON 数组本身，不要包含 ```json 代码块或任何解释文字\n";
        String output = toolMode
                ? "现在调用 submitPlan 工具提交计划。"
                : "输出 JSON 数组：\n[{\"id\": \"...\", \"description\": \"...\", \"executor\": \"...\", \"dependsOn\": [...], \"reason\": \"为什么做这一步（15字以内）\"}]";
        String prompt = """
                Planning policy: worker outputs are private artifacts. For a multi-stage deliverable, create explicit collection, synthesis, review, and final-delivery stages. When view_artifact is available, insert it before an LLM step that must consume a full previous artifact; never put full worker output in a progress event.
                Freshness policy: when the context contains [Freshness policy], the first step MUST use web_search before synthesis. Preserve source identity, URL, publication time, and relevant excerpts.
                你是一个任务规划器。根据用户目标和可用执行器生成执行计划。

                用户目标：%s

                可用执行器（只能使用以下执行器）：
                %s

                要求：
                1. 每步的 executor 必须是上述可用执行器之一
                2. 步骤数 3-5 步，粒度要粗：相关联的工作必须合并为一步
                   （例如「先调研、再验证」合并为一步「调研并验证」；「分析资料、提炼观点」合并为一步「分析提炼」）
                3. 每步 description 必须是简短的动作总结（一句话，15 字以内，说明这一步要完成什么目标），
                   不要写输入输出细节、不要拆碎步骤
                4. 明确步骤间的依赖关系（dependsOn），依赖尽可能少，优先线性推进
                5. 规划目标：每个步骤将来可以独立派发给一个子 Agent 执行（multi-agent 扩展），
                   所以每步要自包含、边界清晰
                6. id 必须唯一、简短（英文/数字/连字符）
                7. dependsOn 只能引用数组中已定义的步骤 id
                8. [B-agentic Memory] requiresMemory 字段：默认 false。仅当此步骤必须参考用户历史偏好、
                   过往相似案例、过往对话要点时才置 true。普通调研/资料整理/撰写步骤一律不填 false。
                   由 ReActLoop 在任务开始时已做一次任务级 memory 召回，重复每步注入既浪费 token 又引入噪声。
                9. [agentic 按需检索] 任务执行中若某一步需要额外历史记忆，LLM 可调用 recallMemory 工具
                   （传 query + topK）在运行时按需召回，不必在规划阶段把 memory 依赖写死。
                10. [UI 展示] 每步加 reason 字段：用一句话（15 字以内）说明"为什么做这一步"，
                    展示给用户理解规划意图（如"先获取事实基础，避免幻觉"）。可省略。

                %s
                """.formatted(goal, availableExecutors, formatRule, output);
        if (!sessionContext.isBlank()) {
            prompt = sessionContext + "\n\n" + prompt;
        }
        return prompt;
    }

    private String callWithConfig(AgentExecutionContext ctx, String userPrompt) {
        String sp = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
        Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
        ModelExecutionOptions executionOptions = ctx.getVariable("modelExecutionOptions")
                instanceof ModelExecutionOptions value ? value : null;
        return chatClient.generate("agent:plan-execute", userPrompt, sp, temp, executionOptions)
                .getContent();
    }

    private List<Map<String, Object>> parseSearchReferences(String output) {
        if (output == null || output.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(output);
            JsonNode results = root.path("results");
            if (!results.isArray()) return List.of();
            List<Map<String, Object>> references = new ArrayList<>();
            for (JsonNode item : results) {
                String title = item.path("title").asText("");
                String url = item.path("url").asText("");
                if (title.isBlank() && url.isBlank()) continue;
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("title", title.isBlank() ? url : title);
                if (!url.isBlank()) ref.put("url", url);
                String source = item.path("source").asText("");
                if (!source.isBlank()) ref.put("source", source);
                String published = item.path("published_date").asText("");
                if (!published.isBlank()) ref.put("publishedDate", published);
                String snippet = item.path("content").asText("");
                if (!snippet.isBlank()) ref.put("snippet", snippet);
                if (item.has("score") && item.get("score").isNumber()) {
                    ref.put("score", item.get("score").asDouble());
                }
                references.add(ref);
            }
            return references;
        } catch (Exception e) {
            log.debug("[PLAN_EXECUTE] search references parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String contextPack(AgentExecutionContext ctx) {
        Object value = ctx.getVariable("sessionContext");
        return value != null ? value.toString() : "";
    }

    /** Persist a bounded, human-readable context ledger for the next step and resume. */
    private int updateDynamicContext(AgentExecutionContext ctx, TaskStep step,
                                     String summary, StepArtifactStore.ArtifactRef artifactRef) {
        Object currentVersion = ctx.getVariable("context.version");
        int version = currentVersion instanceof Number n ? n.intValue() + 1 : 1;
        String previous = contextPack(ctx).trim();
        StringBuilder delta = new StringBuilder("[Dynamic context v").append(version).append("]\n")
                .append("- ").append(step.id()).append(": ").append(truncate(summary, 500));
        if (artifactRef != null) {
            delta.append("\n  artifact: ").append(artifactRef.name());
            if (artifactRef.objectKey() != null && !artifactRef.objectKey().isBlank()) {
                delta.append(" (key=").append(artifactRef.objectKey()).append(")");
            }
        }
        String merged = previous.isBlank() ? delta.toString() : previous + "\n\n" + delta;
        final int maxChars = 16000;
        if (merged.length() > maxChars) {
            int head = 4000;
            String marker = "\n...[older context compacted]...\n";
            int tailLength = maxChars - head - marker.length();
            merged = merged.substring(0, head) + marker
                    + merged.substring(Math.max(head, merged.length() - tailLength));
        }
        ctx.putVariable("sessionContext", merged);
        ctx.putVariable("context.version", version);
        return version;
    }

    private String buildExecutorDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("- llm：LLM 文本生成/分析/总结\n");
        // 后续动态从 dispatcher 获取已注册的执行器
        if (dispatcher.isAvailable("web_search")) {
            sb.append("- web_search：网络搜索\n");
        }
        if (dispatcher.isAvailable("rag_search")) {
            sb.append("- rag_search：检索企业知识库（RAG），适合需要内部知识/资料的问题\n");
        }
        if (dispatcher.isAvailable("view_artifact")) {
            sb.append("- view_artifact：读取已保存的产物全文（输入来自会话上下文中的引用 URL）\n");
        }
        return sb.toString();
    }

    private TaskPlan parsePlan(String response, String goal) {
        return PlanJsonParser.parse(response, goal, executor -> dispatcher.isAvailable(executor));
    }

    private TaskPlan fallbackPlan(String goal) {
        List<TaskStep> steps = List.of(
                TaskStep.pending("analyze", "分析需求并制定策略", "llm", List.of()),
                TaskStep.pending("execute", "执行核心任务", "llm", List.of("analyze")),
                TaskStep.pending("review", "审查并完善结果", "llm", List.of("execute"))
        );
        return new TaskPlan(goal, steps);
    }

    // === 计划校验 ===

    private String validatePlan(TaskPlan plan) {
        // 校验 1：executor 可用性
        for (TaskStep step : plan.steps()) {
            if (!dispatcher.isAvailable(step.executor())) {
                return "Step '" + step.id() + "' uses unavailable executor: " + step.executor();
            }
        }

        // 校验 2：DAG 无环（简单检测）
        for (TaskStep step : plan.steps()) {
            if (step.dependsOn() != null) {
                for (String dep : step.dependsOn()) {
                    boolean exists = plan.steps().stream().anyMatch(s -> s.id().equals(dep));
                    if (!exists) {
                        return "Step '" + step.id() + "' depends on non-existent step: " + dep;
                    }
                }
            }
        }

        return null;  // 校验通过
    }

    // === 契约化执行 ===

    private StepExecutorResult executeStepWithContract(TaskStep step, AgentExecutionContext ctx,
                                                       Map<String, String> pendingRetryHints) {
        StepExecutorResult result = null;
        Map<String, String> inputs = Map.of();
        int attempts = 0;
        long started = System.currentTimeMillis();

        for (int attempt = 0; attempt <= MAX_RETRIES_PER_STEP; attempt++) {
            // 构建输入契约
            StepContract contract = buildContract(step);
            inputs = contextManager.resolve(contract, ctx);

            // 优先注入 reflector RETRY 的改进 hint，其次注入执行失败 hint
            String retryHint = pendingRetryHints.get(step.id());
            if (retryHint != null) {
                inputs.put("_retry_hint", "质量评估未通过，请参考改进方向：" + retryHint);
            } else if (attempt > 0 && result != null) {
                inputs.put("_retry_hint", "上一次执行失败：" + result.error() + "。请改进。");
            }

            // 路由到对应执行器
            result = dispatcher.dispatch(step.executor(), inputs, ctx);
            attempts = attempt + 1;
            if (result.success()) break;

            log.debug("[PLAN_EXECUTE] step={} attempt={} failed", step.id(), attempt);
        }

        ctx.recordStep(StepRecord.from(ctx.getTaskId(), step, ctx.getStepIndex(), inputs,
                result, attempts, started));
        return result;
    }

    private StepContract buildContract(TaskStep step) {
        StepContract base;
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) {
            base = StepContract.noInput(step.id(), "step." + step.id() + ".output");
        } else {
            List<InputBinding> bindings = step.dependsOn().stream()
                    .map(dep -> step.executor().startsWith("view_artifact")
                            ? InputBinding.summary("artifactKey", dep)
                            : InputBinding.summary(dep, dep))
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            base = new StepContract(step.id(), bindings, "step." + step.id() + ".output");
        }
        return withMemoryBinding(base, step);
    }

    /**
     * [B-agentic Memory] 为契约追加记忆检索绑定。
     *
     * <p><b>行为变更</b>：默认不再自动注入历史记忆（agentic Memory）。仅当
     * PlanStep.requiresMemory=true 时才追加 MEMORY binding——LLM 必须显式
     * opt-in 才能拿到任务级一次性以外的 memory 上下文，节省 token 与噪声。
     * 任务级一次性的 memoryBridge.recall 仍由 ReActLoop 触发（line 201-226），
     * 对应第一步执行时 ctx.putVariable("agent.recallResult", ...) 即可。
     *
     * <p>运维可临时回退到旧行为：{@code -Dai.agent.plan.memory-per-step-injection=true}
     * （让 withMemoryBinding 退化到无条件追加）—— 留作紧急回退通道，不建议长期使用。
     */
    private StepContract withMemoryBinding(StepContract contract, TaskStep step) {
        if (!step.requiresMemory()) {
            return contract;  // agentic Memory: 默认不注入
        }
        if (contract.inputs() == null || contract.inputs().stream()
                .noneMatch(b -> b.mode() == ResolutionMode.MEMORY)) {
            List<InputBinding> bindings = new java.util.ArrayList<>(contract.inputs());
            bindings.add(InputBinding.memory("memoryContext",
                    com.yin.ai.platform.core.memory.MemoryType.SEMANTIC, "{goal}", 5));
            return new StepContract(contract.stepId(), bindings, contract.outputKey());
        }
        return contract;
    }

    // === 反思处理 ===

    private ReflectDecision handleReflection(ReflectResult reflection,
                                              AgentExecutionContext ctx,
                                              TaskStep currentStep) {
        switch (reflection.decision()) {
            case CONTINUE -> {
                return ReflectDecision.CONTINUE;
            }
            case RETRY -> {
                ctx.updateStep(currentStep.id(), currentStep.withStatus(StepStatus.PENDING));
                return ReflectDecision.RETRY;
            }
            case REPLAN -> {
                if (reflection.newSteps() != null && !reflection.newSteps().isEmpty()) {
                    TaskPlan current = ctx.getPlan();
                    var allSteps = new ArrayList<>(current.steps());
                    allSteps.addAll(reflection.newSteps());
                    ctx.replacePlan(new TaskPlan(current.goal(), allSteps), false);
                    log.info("[PLAN_EXECUTE] replanned: +{} steps", reflection.newSteps().size());
                }
                return ReflectDecision.REPLAN;
            }
            case ABORT -> {
                return ReflectDecision.ABORT;
            }
            default -> {
                return ReflectDecision.CONTINUE;
            }
        }
    }

    private StepSummary generateStreamingStepSummary(AgentExecutionContext ctx,
                                                      TaskStep step,
                                                      StepResult result,
                                                      String rawOutput,
                                                      String nextAction,
                                                      FluxSink<AgentEvent> sink,
                                                      EventPublisher publisher) {
        StepSummary fallback = stepSummaryGenerator.generate(step, result, rawOutput, nextAction);
        String source = rawOutput == null ? "" : truncate(rawOutput, 12000);
        String prompt = """
                请把下面这个内部步骤结果总结成面向用户的简洁 Markdown。

                要求：
                - 只输出可直接展示的 Markdown 正文，不输出 JSON、代码围栏或字段名模板；
                - 不暴露原始工具响应、数组、调试信息或思维链；
                - 说明完成了什么、得到哪些可靠发现，以及这些发现对任务的意义；
                - 原始内容是检索 JSON 时，只归纳信息和来源，不复制 JSON；
                - 控制在 80～350 字，允许标题、短段落和项目符号。

                步骤：%s
                下一步：%s
                内部结果：
                %s
                """.formatted(step.description(), nextAction, source);
        StringBuilder content = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        boolean[] safe = {false};
        boolean[] blocked = {false};
        int[] ordinal = {0};
        try {
            Double temp = ctx.getVariable("temperature") instanceof Double t ? Math.min(t, 0.4d) : 0.2d;
            ModelExecutionOptions executionOptions = ctx.getVariable("modelExecutionOptions")
                    instanceof ModelExecutionOptions value ? value : null;
            chatClient.stream("agent:step-summary", prompt,
                            "你是 Agent 的用户可见进度编辑器。只报告结论，不输出原始 JSON 或推理过程。",
                            temp, executionOptions)
                    .filter(packet -> "text".equals(packet.getType()) && packet.getContent() != null)
                    .map(packet -> packet.getContent().toString())
                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                    .doOnNext(chunk -> {
                        content.append(chunk);
                        if (blocked[0]) return;
                        if (safe[0]) {
                            emit(sink, publisher, AgentEvent.stepSummaryDelta(
                                    ctx.getTaskId(), step.id(), chunk, ordinal[0]++));
                            return;
                        }
                        pending.append(chunk);
                        String candidate = pending.toString().stripLeading();
                        if (candidate.isEmpty()) return;
                        char first = candidate.charAt(0);
                        if (first == '{' || first == '[') {
                            blocked[0] = true;
                            return;
                        }
                        if (first == '`' && candidate.length() < 7) return;
                        if (candidate.toLowerCase(java.util.Locale.ROOT).startsWith("```json")) {
                            blocked[0] = true;
                            return;
                        }
                        safe[0] = true;
                        emit(sink, publisher, AgentEvent.stepSummaryDelta(
                                ctx.getTaskId(), step.id(), pending.toString(), ordinal[0]++));
                        pending.setLength(0);
                    })
                    .blockLast();
        } catch (RuntimeException e) {
            log.warn("[PLAN_EXECUTE] summary streaming failed step={}: {}", step.id(), e.getMessage());
        }

        String markdown = stepSummaryGenerator.toReadableMarkdown(content.toString());
        if (blocked[0] || markdown.isBlank()) {
            markdown = fallback.content();
            if (!markdown.isBlank()) {
                emit(sink, publisher, AgentEvent.stepSummaryDelta(
                        ctx.getTaskId(), step.id(), markdown, ordinal[0]++));
            }
        }
        StepSummary normalized = stepSummaryGenerator.generate(step, result, markdown, nextAction);
        return new StepSummary(step.id(), normalized.title(), markdown,
                normalized.findings(), normalized.nextAction());
    }

    /** LLM is the final judge; deterministic checks only avoid needless judge calls for obvious non-artifacts. */
    private boolean shouldPublishIntermediateArtifact(AgentExecutionContext ctx,
                                                      TaskStep step,
                                                      String output) {
        if (output == null || output.length() < 600 || isToolExecutor(step.executor())
                || isFinalArtifactStep(ctx, step) || stepSummaryGenerator.looksLikeJson(output)) {
            return false;
        }
        String prompt = """
                判断这个内部步骤输出是否值得作为“中间版本”提供给用户审阅和回滚。
                只有结构完整、可供用户独立审阅或回滚的中间产物才返回 PUBLISH。
                原始检索结果、JSON、工具输出、简单分析、步骤总结、读取回显和最终产物一律返回 SKIP。
                只返回 PUBLISH 或 SKIP。

                步骤：%s
                输出：
                %s
                """.formatted(step.description(), truncate(output, 8000));
        try {
            String decision = callPolicyModel(ctx,
                    "你是严格的内容版本发布门禁，只能回答 PUBLISH 或 SKIP。", prompt, 0.0d);
            return "PUBLISH".equalsIgnoreCase(decision == null ? "" : decision.trim());
        } catch (RuntimeException e) {
            log.warn("[PLAN_EXECUTE] intermediate artifact judge failed step={}: {}", step.id(), e.getMessage());
            return false;
        }
    }

    private String ensureValidFinalArtifact(AgentExecutionContext ctx, String candidate) {
        String readable = stepSummaryGenerator.toReadableMarkdown(candidate);
        if (!artifactRequired(ctx)) {
            if (readable.isBlank()) throw new IllegalStateException("最终结果为空或不是可展示内容，任务未标记为完成");
            return readable;
        }
        if (isArtifactReady(ctx, readable)) return readable;

        String repairInstruction = stringVariable(ctx, "artifactRepairPrompt", """
                当前候选产物没有通过交付检查。请根据任务目标、步骤结论和候选内容，修复为可独立阅读的完整 Markdown 产物。
                只能使用已经验证的来源，不得编造 URL；不要输出 JSON、工具参数、工作流说明或道歉。
                """);
        String prompt = """
                %s

                任务目标：%s

                步骤结论：
                %s

                候选内容：
                %s

                已验证来源：
                %s
                """.formatted(repairInstruction, ctx.getGoal(), truncate(stepSummaryContent(ctx), 6000),
                truncate(candidate, 12000), formatVerifiedSources(ctx));
        String repaired = callPolicyModel(ctx,
                stringVariable(ctx, "artifactSystemPrompt",
                        "你是最终交付校验器，必须返回完整的 Markdown 产物。"), prompt, 0.2d);
        repaired = stepSummaryGenerator.toReadableMarkdown(repaired);
        if (!isArtifactReady(ctx, repaired)) {
            throw new IllegalStateException("最终产物未通过完整性检查，任务未标记为完成，请从断点重试");
        }
        return repaired;
    }

    private boolean isArtifactReady(AgentExecutionContext ctx, String value) {
        int minimumChars;
        try {
            minimumChars = Integer.parseInt(stringVariable(ctx, "artifactMinimumChars", "500"));
        } catch (NumberFormatException ignored) {
            minimumChars = 500;
        }
        if (value == null || value.length() < Math.max(1, minimumChars)
                || stepSummaryGenerator.looksLikeJson(value)) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String marker : List.of("无法完成", "查询失败", "搜索失败", "服务不可用", "请稍后重试",
                "web_search failed", "no ref found", "invalid_request_error")) {
            if (lower.contains(marker.toLowerCase(java.util.Locale.ROOT))) return false;
        }
        long meaningfulLines = value.lines().map(String::trim).filter(line -> line.length() >= 20).count();
        if (meaningfulLines < 3 || lower.matches("(?s).*\\\"query\\\"\\s*:.*\\\"results\\\"\\s*:.*")) {
            return false;
        }
        return validateFinalEvidence(ctx, value);
    }

    private boolean validateFinalEvidence(AgentExecutionContext ctx, String markdown) {
        if (!requiresFinalEvidenceGate(ctx)) return true;
        ClaimCitationAnalyzer.Analysis analysis = ClaimCitationAnalyzer.analyze(markdown);
        ctx.putVariable("quality.claimCount", analysis.claimCount());
        ctx.putVariable("quality.supportedClaimCount", analysis.supportedClaimCount());
        ctx.putVariable("quality.claimCitationCoverage", analysis.coverage());
        if (!analysis.meets(MIN_FINAL_CLAIM_CITATION_COVERAGE)) {
            log.warn("[PLAN_EXECUTE] final claim citation coverage below threshold taskId={}, coverage={}, claims={}",
                    ctx.getTaskId(), analysis.coverage(), analysis.claimCount());
            return false;
        }

        Set<String> verifiedUrls = verifiedSourceUrls(ctx);
        Set<String> citedUrls = extractUrls(markdown);
        List<String> foreignUrls = citedUrls.stream()
                .filter(url -> !verifiedUrls.contains(normalizeUrl(url)))
                .toList();
        ctx.putVariable("quality.verifiedSourceCount", verifiedUrls.size());
        ctx.putVariable("quality.unverifiedUrlCount", foreignUrls.size());
        if (!foreignUrls.isEmpty()) {
            log.warn("[PLAN_EXECUTE] final artifact contains unverified URLs taskId={}, urls={}",
                    ctx.getTaskId(), foreignUrls);
            return false;
        }
        // Web evidence must remain visible in the delivered artifact. RAG-only
        // tasks may use [E#] citations and therefore have no URL in the artifact.
        if (!verifiedUrls.isEmpty() && citedUrls.isEmpty()) return false;
        if (!verifiedUrls.isEmpty()) {
            if (claimEvidenceQualityJudge == null) return false;
            List<ClaimEvidenceQualityJudge.EvidenceSource> evidence = verifiedEvidence(ctx);
            ClaimEvidenceQualityJudge.Quality quality = claimEvidenceQualityJudge.evaluate(markdown, evidence);
            if (quality == null) return false;
            ctx.putVariable("quality.faithfulness", quality.faithfulness());
            ctx.putVariable("quality.evidenceSupportRate", quality.evidenceSupportRate());
            ctx.putVariable("quality.sourceTrustScore", quality.sourceTrustScore());
            ctx.putVariable("quality.freshnessScore", quality.freshnessScore());
            ctx.putVariable("quality.conflictRate", quality.conflictRate());
            ctx.putVariable("quality.unsupportedFactRate", quality.unsupportedFactRate());
            ctx.putVariable("quality.groundingStatus", quality.passes() ? "PASSED" : "REJECTED");
            return quality.passes();
        }
        return true;
    }

    private List<ClaimEvidenceQualityJudge.EvidenceSource> verifiedEvidence(AgentExecutionContext ctx) {
        List<ClaimEvidenceQualityJudge.EvidenceSource> evidence = new ArrayList<>();
        if (ctx.getPlan() == null) return evidence;
        for (TaskStep step : ctx.getPlan().steps()) {
            if (step.executor() == null || !step.executor().startsWith("web_search")) continue;
            for (Map<String, Object> ref : parseSearchReferences(step.output())) {
                evidence.add(new ClaimEvidenceQualityJudge.EvidenceSource(
                        String.valueOf(ref.getOrDefault("title", "")),
                        String.valueOf(ref.getOrDefault("url", "")),
                        String.valueOf(ref.getOrDefault("source", "")),
                        String.valueOf(ref.getOrDefault("snippet", "")),
                        String.valueOf(ref.getOrDefault("publishedDate", ""))));
            }
        }
        return evidence;
    }

    private boolean requiresFinalEvidenceGate(AgentExecutionContext ctx) {
        Object explicit = ctx.getVariable("artifactEvidenceRequired");
        if (explicit != null) {
            return explicit instanceof Boolean bool ? bool : Boolean.parseBoolean(explicit.toString());
        }
        if (contextPack(ctx).contains("[Freshness policy]")) return true;
        return ctx.getPlan() != null && ctx.getPlan().steps().stream()
                .map(TaskStep::executor)
                .filter(java.util.Objects::nonNull)
                .anyMatch(executor -> executor.startsWith("web_search") || executor.startsWith("rag_search"));
    }

    private Set<String> verifiedSourceUrls(AgentExecutionContext ctx) {
        if (ctx.getPlan() == null) return Set.of();
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        for (TaskStep step : ctx.getPlan().steps()) {
            if (step.executor() == null || !step.executor().startsWith("web_search")
                    || step.output() == null || step.output().isBlank()) continue;
            for (Map<String, Object> reference : parseSearchReferences(step.output())) {
                Object url = reference.get("url");
                if (url != null && !url.toString().isBlank()) urls.add(normalizeUrl(url.toString()));
            }
        }
        return Set.copyOf(urls);
    }

    private String formatVerifiedSources(AgentExecutionContext ctx) {
        Set<String> urls = verifiedSourceUrls(ctx);
        return urls.isEmpty() ? "（无可用 URL；仅允许使用已有 [E编号] 证据）"
                : urls.stream().map(url -> "- " + url).collect(java.util.stream.Collectors.joining("\n"));
    }

    private Set<String> extractUrls(String value) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find()) urls.add(normalizeUrl(matcher.group()));
        return Set.copyOf(urls);
    }

    private String normalizeUrl(String value) {
        String url = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        while (!url.isEmpty() && ".,;:!?，。；：！？)]}".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String callPolicyModel(AgentExecutionContext ctx, String systemPrompt,
                                   String userPrompt, double temperature) {
        ModelExecutionOptions executionOptions = ctx.getVariable("modelExecutionOptions")
                instanceof ModelExecutionOptions value ? value : null;
        String content = chatClient.generate("agent:policy", userPrompt, systemPrompt,
                temperature, executionOptions).getContent();
        return content == null ? "" : content.trim();
    }

    private boolean isToolExecutor(String executor) {
        return executor != null && !executor.isBlank() && !"llm".equalsIgnoreCase(executor);
    }

    // === 汇总 ===

    private String summarize(AgentExecutionContext ctx) {
        TaskPlan plan = ctx.getPlan();
        if (plan == null) return "";
        // Prefer the explicit final artifact step. Dynamic search/readback steps may be
        // appended after it and must never replace the actual deliverable.
        if (artifactRequired(ctx)) {
            for (int i = plan.steps().size() - 1; i >= 0; i--) {
                TaskStep step = plan.steps().get(i);
                if (isFinalArtifactStep(ctx, step) && step.isCompleted()
                        && step.output() != null && !step.output().isBlank()) {
                    return ensureMarkdown(step.output());
                }
            }
        }
        // For other tasks prefer a model synthesis over raw tool output.
        for (int i = plan.steps().size() - 1; i >= 0; i--) {
            TaskStep step = plan.steps().get(i);
            if ("llm".equalsIgnoreCase(step.executor())
                    && step.output() != null && !step.output().isBlank()) {
                return ensureMarkdown(step.output());
            }
        }
        return "";
    }

    private String summarizeStep(String output, StepArtifactStore.ArtifactRef ref) {
        String summary = truncate(output, 220);
        if (ref != null) {
            summary += "（中间产物已保存）";
        }
        return summary;
    }

    private String ensureMarkdown(String output) {
        return output == null ? "" : output.trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
