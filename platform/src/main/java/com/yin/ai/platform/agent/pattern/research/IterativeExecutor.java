package com.yin.ai.platform.agent.pattern.research;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.pattern.*;
import com.yin.ai.platform.agent.pattern.context.ContextManager;
import com.yin.ai.platform.agent.pattern.context.InputBinding;
import com.yin.ai.platform.agent.pattern.context.ResolutionMode;
import com.yin.ai.platform.agent.pattern.context.StepContract;
import com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher;
import com.yin.ai.platform.core.runtime.research.IterativeExecutionResult;
import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.reflect.ReflectDecision;
import com.yin.ai.platform.agent.pattern.reflect.ReflectResult;
import com.yin.ai.platform.agent.pattern.reflect.StepReflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 迭代执行器 — DeepResearch 的第三阶段。
 * <p>
 * 与 PlanExecute 的逐步执行的核心区别：
 * 1. 每步执行后由 Reflector 评估，可能触发 REPLAN（追加研究轮次）
 * 2. 支持动态 Plan 变更：执行中发现信息不足 → 追加搜索步骤
 * 3. 有最大迭代轮次限制（防止无限循环）
 * 4. 支持 SKIP_TO_SYNTHESIS：信息充分时提前进入综合阶段
 * <p>
 * 执行循环：
 * while (hasNextStep && round < maxRounds):
 *   step = nextPending()
 *   inputs = contextManager.resolve(contract)
 *   result = dispatcher.dispatch(step.executor, inputs)
 *   reflect = reflector.reflect(step, result)
 *   switch (reflect.decision):
 *     CONTINUE → next step
 *     RETRY → re-execute with hint
 *     REPLAN → append new steps, continue
 *     SKIP_TO_SYNTHESIS → break
 *     ABORT → throw
 */
public class IterativeExecutor {

    private static final Logger log = LoggerFactory.getLogger(IterativeExecutor.class);
    private static final int MAX_ROUNDS = 8;
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final StepExecutorDispatcher dispatcher;
    private final ContextManager contextManager;
    private final StepReflector reflector;

    public IterativeExecutor(StepExecutorDispatcher dispatcher,
                              ContextManager contextManager,
                              StepReflector reflector) {
        this.dispatcher = dispatcher;
        this.contextManager = contextManager;
        this.reflector = reflector;
    }

    /**
     * 迭代执行研究计划。
     *
     * @param ctx 执行上下文（含 Plan）
     * @return 执行摘要（所有步骤输出的汇总）
     */
    public IterativeExecutionResult execute(AgentExecutionContext ctx) {
        TaskPlan plan = ctx.getPlan();
        if (plan == null) {
            return IterativeExecutionResult.failed("No plan available");
        }

        int round = 0;
        int totalSteps = plan.totalSteps();
        String unresolvedFailure = null;

        while (round < MAX_ROUNDS) {
            TaskStep step = ctx.getPlan().nextPending();
            if (step == null) {
                break;  // 所有步骤完成
            }

            round++;
            int stepIndex = ctx.getPlan().steps().indexOf(step);
            ctx.setCurrentStep(step.id(), stepIndex);
            saveCheckpoint(ctx);

            // 发射步骤开始事件
            ctx.getEventPublisher().publish(
                    AgentEvent.stepStarted(ctx.getTaskId(), step.id(), step.description()));
            ctx.getEventPublisher().publish(
                    AgentEvent.progress(ctx.getTaskId(), stepIndex + 1,
                            ctx.getPlan().totalSteps(), step.description()));

            // 执行步骤（含重试逻辑）
            var reasoning = ReasoningOptionsSupport.resolve(ctx, step.stepType());
            ctx.getEventPublisher().publish(
                    AgentEvent.reasoningStarted(ctx.getTaskId(), step.id(), reasoning));
            StepExecutorResult result = executeWithRetry(step, ctx);
            ctx.getEventPublisher().publish(
                    AgentEvent.reasoningCompleted(
                            ctx.getTaskId(), step.id(), reasoning, result.tokensUsed()));

            if (result.success()) {
                // 保存输出
                ctx.updateStep(step.id(), step.withOutput(result.output()));
                ctx.putVariable("step." + step.id() + ".output", result.output());
                StepArtifactStore.ArtifactRef artifactRef =
                        ctx.saveStepArtifact(step.id(), stepIndex, result.output());
                int contextVersion = updateDynamicContext(ctx, step, result.output(), artifactRef);
                saveCheckpoint(ctx);
                ctx.getEventPublisher().publish(AgentEvent.contextUpdated(
                        ctx.getTaskId(), step.id(), contextVersion,
                        truncate(result.output(), 220),
                        artifactRef != null ? artifactRef.objectKey() : null,
                        artifactRef != null ? artifactRef.url() : null,
                        result.output() == null ? 0 : result.output().length()));
                ctx.getEventPublisher().publish(
                        AgentEvent.stepCompleted(ctx.getTaskId(), step.id(),
                                truncate(result.output(), 200)));

                // Reflector 评估
                ReflectResult reflection = reflector.reflect(step, result.output(), ctx);
                ReflectDecision action = handleReflection(reflection, ctx);

                if (action == ReflectDecision.SKIP_TO_SYNTHESIS) {
                    log.info("[ITER-EXEC] skipping to synthesis: {}", reflection.reason());
                    skipRemainingSteps(ctx);
                    break;
                }
                if (action == ReflectDecision.ABORT) {
                    return IterativeExecutionResult.failed("Aborted: " + reflection.reason());
                }

            } else {
                // 步骤失败
                ctx.updateStep(step.id(), step.withError(result.error()));
                saveCheckpoint(ctx);
                ctx.getEventPublisher().publish(
                        AgentEvent.stepFailed(ctx.getTaskId(), step.id(), result.error()));
                log.warn("[ITER-EXEC] step failed: {} - {}", step.id(), result.error());
                unresolvedFailure = "Step " + step.id() + " failed: " + result.error();
                break;
            }
        }

        if (unresolvedFailure != null) {
            return IterativeExecutionResult.failed(unresolvedFailure);
        }

        return IterativeExecutionResult.success(
                ctx.getPlan().completedCount(),
                ctx.getPlan().totalSteps(),
                round);
    }

    private void saveCheckpoint(AgentExecutionContext ctx) {
        ctx.getContextStore().saveSnapshot(ctx.getTaskId(), ctx.snapshot());
    }

    private int updateDynamicContext(AgentExecutionContext ctx, TaskStep step,
                                     String output, StepArtifactStore.ArtifactRef artifactRef) {
        Object current = ctx.getVariable("context.version");
        int version = current instanceof Number n ? n.intValue() + 1 : 1;
        String previous = String.valueOf(ctx.getVariable("sessionContext") == null
                ? "" : ctx.getVariable("sessionContext")).trim();
        StringBuilder delta = new StringBuilder("[Dynamic context v").append(version).append("]\n")
                .append("- ").append(step.id()).append(": ").append(truncate(output, 500));
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

    private StepExecutorResult executeWithRetry(TaskStep step, AgentExecutionContext ctx) {
        StepExecutorResult result = null;
        Map<String, String> inputs = Map.of();
        int attempts = 0;
        long started = System.currentTimeMillis();

        for (int attempt = 0; attempt <= MAX_RETRIES_PER_STEP; attempt++) {
            // 解析输入契约
            StepContract contract = buildContract(step, ctx);
            inputs = contextManager.resolve(contract, ctx);

            // 如果有重试 hint，注入到输入中
            if (attempt > 0 && result != null) {
                inputs.put("_retry_hint", "上一次执行质量不足，请改进。错误：" + result.error());
            }

            // 执行
            result = dispatcher.dispatch(step.executor(), inputs, ctx);
            attempts = attempt + 1;
            if (result.success()) break;

            log.debug("[ITER-EXEC] step={} attempt={} failed: {}",
                    step.id(), attempt, result.error());
        }

        ctx.recordStep(StepRecord.from(ctx.getTaskId(), step, ctx.getStepIndex(), inputs,
                result, attempts, started));
        return result;
    }

    private ReflectDecision handleReflection(ReflectResult reflection, AgentExecutionContext ctx) {
        switch (reflection.decision()) {
            case CONTINUE -> {
                return ReflectDecision.CONTINUE;
            }
            case RETRY -> {
                // 回退步骤状态为 PENDING，下次循环重新执行
                TaskStep step = ctx.getPlan().steps().stream()
                        .filter(s -> s.id().equals(ctx.getCurrentStepId()))
                        .findFirst().orElse(null);
                if (step != null) {
                    ctx.updateStep(step.id(), step.withStatus(StepStatus.PENDING));
                }
                return ReflectDecision.RETRY;
            }
            case REPLAN -> {
                // 追加新步骤到 Plan
                if (reflection.newSteps() != null && !reflection.newSteps().isEmpty()) {
                    TaskPlan current = ctx.getPlan();
                    var allSteps = new java.util.ArrayList<>(current.steps());
                    allSteps.addAll(reflection.newSteps());
                    ctx.setPlan(new TaskPlan(current.goal(), allSteps));
                    log.info("[ITER-EXEC] replanned: added {} steps",
                            reflection.newSteps().size());
                }
                return ReflectDecision.REPLAN;
            }
            case SKIP_TO_SYNTHESIS -> {
                return ReflectDecision.SKIP_TO_SYNTHESIS;
            }
            case ABORT -> {
                return ReflectDecision.ABORT;
            }
            default -> {
                return ReflectDecision.CONTINUE;
            }
        }
    }

    private void skipRemainingSteps(AgentExecutionContext ctx) {
        TaskPlan plan = ctx.getPlan();
        for (TaskStep step : plan.steps()) {
            if (step.isPending()) {
                ctx.updateStep(step.id(), step.withStatus(StepStatus.SKIPPED));
            }
        }
    }

    private StepContract buildContract(TaskStep step, AgentExecutionContext ctx) {
        // 根据依赖关系自动构建输入绑定
        StepContract base;
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) {
            base = StepContract.noInput(step.id(), "step." + step.id() + ".output");
        } else {
            List<InputBinding> bindings = step.dependsOn().stream()
                    .map(dep -> InputBinding.summary(dep, dep))  // 默认用 SUMMARY 模式节省 token
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            base = new StepContract(step.id(), bindings, "step." + step.id() + ".output");
        }
        // [B-agentic Memory] 每步默认不再注入 memory — 任务级一次性仍由 ReActLoop 触发。
        // LLM 显式 opt-in (step.requiresMemory=true) 时才追加 MEMORY binding
        if (!step.requiresMemory()) {
            return base;
        }
        if (base.inputs() == null || base.inputs().stream()
                .noneMatch(b -> b.mode() == ResolutionMode.MEMORY)) {
            List<InputBinding> bindings = new java.util.ArrayList<>(base.inputs());
            bindings.add(InputBinding.memory("memoryContext",
                    com.yin.ai.platform.core.memory.MemoryType.SEMANTIC, "{goal}", 5));
            return new StepContract(base.stepId(), bindings, base.outputKey());
        }
        return base;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
