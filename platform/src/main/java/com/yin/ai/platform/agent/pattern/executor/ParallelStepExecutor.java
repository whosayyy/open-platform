package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.StepStatus;
import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.agent.pattern.context.ContextManager;
import com.yin.ai.platform.agent.pattern.context.InputBinding;
import com.yin.ai.platform.agent.pattern.context.StepContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 并行步骤执行器 — 拓扑排序 + CompletableFuture 并发。
 * <p>
 * 对 Plan 中的步骤进行拓扑排序，将无依赖的步骤并行提交执行。
 * 每轮并行执行完成后，检查新解锁的步骤，继续下一轮。
 * <p>
 * 适用场景：PlanExecute / DeepResearch 中多个搜索步骤可并行。
 */
public class ParallelStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelStepExecutor.class);

    private final StepExecutorDispatcher dispatcher;
    private final ContextManager contextManager;
    private final ExecutorService executorService;

    public ParallelStepExecutor(StepExecutorDispatcher dispatcher,
                                 ContextManager contextManager,
                                 ExecutorService executorService) {
        this.dispatcher = dispatcher;
        this.contextManager = contextManager;
        this.executorService = executorService;
    }

    /**
     * 并行执行所有可并行步骤。
     * 按拓扑层级分批：同层无依赖步骤并行，层间串行。
     *
     * @param ctx 执行上下文（含 Plan）
     * @return 执行结果 Map（stepId → output）
     */
    public Map<String, StepExecutorResult> executeParallel(AgentExecutionContext ctx) {
        TaskPlan plan = ctx.getPlan();
        if (plan == null) return Map.of();

        List<List<TaskStep>> layers = topologicalSort(plan);
        Map<String, StepExecutorResult> results = new LinkedHashMap<>();

        log.info("[PARALLEL] taskId={} plan has {} layers", ctx.getTaskId(), layers.size());

        for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
            List<TaskStep> layer = layers.get(layerIdx);
            List<TaskStep> pending = layer.stream()
                    .filter(TaskStep::isPending)
                    .toList();

            if (pending.isEmpty()) continue;

            log.debug("[PARALLEL] layer={} executing {} steps in parallel: {}",
                    layerIdx, pending.size(),
                    pending.stream().map(TaskStep::id).toList());

            // 并行提交本层所有 pending 步骤
            List<CompletableFuture<Void>> futures = pending.stream()
                    .map(step -> CompletableFuture.runAsync(() -> {
                        int stepIndex = plan.steps().indexOf(step);
                        StepExecutorResult result;
                        try (AutoCloseable ignored = ctx.openStepScope(step.id(), stepIndex)) {
                            StepContract contract = buildContract(step);
                            Map<String, String> inputs = contextManager.resolve(contract, ctx);
                            result = dispatcher.dispatch(step.executor(), inputs, ctx);
                        } catch (Exception e) {
                            result = StepExecutorResult.fail(e.getMessage(), 0);
                        }

                        synchronized (results) {
                            results.put(step.id(), result);
                        }

                        // 更新 Plan 状态
                        if (result.success()) {
                            ctx.updateStep(step.id(), step.withOutput(result.output()));
                            ctx.putVariable("step." + step.id() + ".output", result.output());
                        } else {
                            ctx.updateStep(step.id(), step.withError(result.error()));
                        }
                    }, executorService))
                    .toList();

            // 等待本层全部完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        return results;
    }

    /** Executes only ready, read-only search nodes; LLM and side-effect nodes stay sequential. */
    public Map<String, StepExecutorResult> executeReadySearchSteps(AgentExecutionContext ctx) {
        TaskPlan plan = ctx.getPlan();
        if (plan == null) return Map.of();
        List<TaskStep> ready = plan.steps().stream()
                .filter(TaskStep::isPending)
                .filter(step -> step.executor() != null
                        && (step.executor().startsWith("web_search") || step.executor().startsWith("rag_search")))
                .filter(step -> step.dependsOn() == null || step.dependsOn().stream().allMatch(dep ->
                        plan.steps().stream().filter(candidate -> candidate.id().equals(dep))
                                .findFirst().map(TaskStep::isCompleted).orElse(false)))
                .toList();
        if (ready.size() < 2) return Map.of();

        Map<String, StepExecutorResult> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = ready.stream().map(step -> CompletableFuture.runAsync(() -> {
            int index = plan.steps().indexOf(step);
            try (AutoCloseable ignored = ctx.openStepScope(step.id(), index)) {
                ctx.getEventPublisher().publish(com.yin.ai.platform.agent.event.AgentEvent.stepStarted(
                        ctx.getTaskId(), step.id(), step.description()));
                StepExecutorResult result = dispatcher.dispatch(step.executor(),
                        contextManager.resolve(buildContract(step), ctx), ctx);
                results.put(step.id(), result);
                if (result.success()) {
                    ctx.updateStep(step.id(), step.withOutput(result.output()));
                    ctx.putVariable("step." + step.id() + ".output", result.output());
                    ctx.saveStepArtifact(step.id(), index, result.output());
                    ctx.getEventPublisher().publish(com.yin.ai.platform.agent.event.AgentEvent.stepCompleted(
                            ctx.getTaskId(), step.id(), result.output()));
                } else {
                    ctx.updateStep(step.id(), step.withError(result.error()));
                    ctx.getEventPublisher().publish(com.yin.ai.platform.agent.event.AgentEvent.stepFailed(
                            ctx.getTaskId(), step.id(), result.error()));
                }
            } catch (Exception e) {
                results.put(step.id(), StepExecutorResult.fail(e.getMessage(), 0));
            }
        }, executorService)).toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return Map.copyOf(results);
    }

    /**
     * 拓扑排序：将步骤按依赖关系分层。
     * 同层步骤无互相依赖，可并行执行。
     */
    private List<List<TaskStep>> topologicalSort(TaskPlan plan) {
        List<TaskStep> allSteps = plan.steps();
        Map<String, TaskStep> stepMap = allSteps.stream()
                .collect(Collectors.toMap(TaskStep::id, s -> s));

        // 计算入度
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (TaskStep step : allSteps) {
            inDegree.put(step.id(), step.dependsOn() != null ? step.dependsOn().size() : 0);
        }

        List<List<TaskStep>> layers = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        while (processed.size() < allSteps.size()) {
            // 找出入度为 0 且未处理的步骤
            List<TaskStep> currentLayer = allSteps.stream()
                    .filter(s -> !processed.contains(s.id()))
                    .filter(s -> inDegree.getOrDefault(s.id(), 0) == 0)
                    .toList();

            if (currentLayer.isEmpty()) {
                // 有环或依赖缺失，将剩余步骤作为最后一层
                List<TaskStep> remaining = allSteps.stream()
                        .filter(s -> !processed.contains(s.id()))
                        .toList();
                layers.add(remaining);
                log.warn("[PARALLEL] cycle detected, forcing remaining {} steps", remaining.size());
                break;
            }

            layers.add(currentLayer);
            for (TaskStep step : currentLayer) {
                processed.add(step.id());
                // 减少依赖此步骤的后续步骤的入度
                for (TaskStep other : allSteps) {
                    if (other.dependsOn() != null && other.dependsOn().contains(step.id())) {
                        inDegree.merge(other.id(), -1, Integer::sum);
                    }
                }
            }
        }

        return layers;
    }

    private StepContract buildContract(TaskStep step) {
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return StepContract.noInput(step.id(), "step." + step.id() + ".output");
        }
        List<InputBinding> bindings = step.dependsOn().stream()
                .map(dep -> InputBinding.summary(dep, dep))
                .toList();
        return new StepContract(step.id(), bindings, "step." + step.id() + ".output");
    }
}
