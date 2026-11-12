package com.yin.ai.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.executor.AgentExecutor;
import com.yin.ai.platform.agent.executor.StepResult;
import com.yin.ai.platform.agent.hitl.HitlCheckpoint;
import com.yin.ai.platform.agent.hitl.HitlDecision;
import com.yin.ai.platform.agent.hitl.HitlPolicy;
import com.yin.ai.platform.agent.hitl.HitlStore;
import com.yin.ai.platform.agent.memory.AgentMemoryBridge;
import com.yin.ai.platform.agent.rag.AgentRagBridge;
import com.yin.ai.platform.agent.observer.Observation;
import com.yin.ai.platform.observe.OnlineQualityRecorder;
import com.yin.ai.platform.core.rag.RetrievedDocument;
import com.yin.ai.platform.rag.enhanced.RagRetrievalSnapshot;
import com.yin.ai.platform.rag.trigger.RAGTriggerDecisionEngine;
import com.yin.ai.platform.agent.observer.Observer;
import com.yin.ai.platform.agent.planner.Plan;
import com.yin.ai.platform.agent.planner.Planner;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.reflector.Decision;
import com.yin.ai.platform.agent.reflector.Reflector;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * ReAct Loop 状态机 — Agent Runtime 的核心编排器。
 * <p>
 * 循环: IDLE → PLANNING → ACTING → OBSERVING → REFLECTING → (loop to ACTING)
 * <p>
 * 设计原则:
 * <ul>
 *   <li>不依赖 Spring — 纯 Java + SLF4J</li>
 *   <li>Planner/Executor/Observer/Reflector 均为 SPI，可独立替换</li>
 *   <li>终止条件: maxSteps / timeout / terminal state / HITL reject</li>
 * </ul>
 */
public class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private final Planner planner;
    private final AgentExecutor executor;
    private final Observer observer;
    private final Reflector reflector;
    private AgentMemoryBridge memoryBridge;
    private AgentRagBridge ragBridge;
    private RAGTriggerDecisionEngine ragTrigger;
    private CheckpointCallback checkpointCallback;
    private ProgressCallback progressCallback;
    private HitlPolicy hitlPolicy;
    private HitlStore hitlStore;
    private OnlineQualityRecorder onlineQualityRecorder;

    public ReActLoop(Planner planner, AgentExecutor executor, Observer observer, Reflector reflector) {
        this.planner = planner;
        this.executor = executor;
        this.observer = observer;
        this.reflector = reflector;
    }

    public void setMemoryBridge(AgentMemoryBridge bridge) {
        this.memoryBridge = bridge;
    }

    /** 在线检索质量采集器（可选注入；null 时跳过采集，不影响执行）。 */
    public void setOnlineQualityRecorder(OnlineQualityRecorder recorder) {
        this.onlineQualityRecorder = recorder;
    }

    public void setRagBridge(AgentRagBridge bridge) {
        this.ragBridge = bridge;
    }

    public void setRagTrigger(RAGTriggerDecisionEngine trigger) {
        this.ragTrigger = trigger;
    }

    public void setCheckpointCallback(CheckpointCallback callback) {
        this.checkpointCallback = callback;
    }

    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    public void setHitlPolicy(HitlPolicy policy) {
        this.hitlPolicy = policy;
    }

    public void setHitlStore(HitlStore store) {
        this.hitlStore = store;
    }

    /**
     * 执行 ReAct Loop（同步/阻塞）。
     *
     * @param ctx Agent 执行上下文
     * @return 构建好的 AgentResult
     */
    public AgentResult enter(AgentContext ctx) {
        ctx.markStarted();

        // ── HITL 恢复：从审批点继续，跳过规划阶段 ──
        Plan hitlPlan = ctx.getVariable("agent.hitlPlan", Plan.class);
        if (hitlPlan != null && hitlPlan.hasSteps()) {
            @SuppressWarnings("unchecked")
            List<StepTrace> hitlTraces = (List<StepTrace>) ctx.getVariable("agent.hitlStepTraces");
            Integer hitlStepIdx = ctx.getVariable("agent.hitlStepIndex", Integer.class);
            int startIdx = hitlStepIdx != null ? hitlStepIdx : 0;
            // 清除恢复标记，防止重复恢复
            ctx.removeVariable("agent.hitlPlan");
            ctx.removeVariable("agent.hitlStepTraces");
            ctx.removeVariable("agent.hitlStepIndex");
            return runStepLoop(ctx, hitlPlan, hitlTraces != null ? hitlTraces : new ArrayList<>(), startIdx);
        }

        // ── checkpoint 恢复：复用已生成的计划，从最近完成步骤之后继续 ──
        Plan resumePlan = ctx.getVariable("agent.resumePlan", Plan.class);
        if (resumePlan != null && resumePlan.hasSteps()) {
            @SuppressWarnings("unchecked")
            List<StepTrace> resumeTraces = (List<StepTrace>) ctx.getVariable("agent.resumeStepTraces");
            Integer resumeStepIndex = ctx.getVariable("agent.resumeStepIndex", Integer.class);
            int startIdx = resumeStepIndex != null
                    ? Math.max(0, Math.min(resumeStepIndex, resumePlan.steps().size())) : 0;
            ctx.removeVariable("agent.resumePlan");
            ctx.removeVariable("agent.resumeStepTraces");
            ctx.removeVariable("agent.resumeStepIndex");
            // 断点重试通常从新建的 IDLE 上下文开始，先进入 PLANNING 以复用通用执行状态机。
            if (ctx.getState() == AgentState.IDLE) {
                ctx.transitionTo(AgentState.PLANNING);
            }
            ctx.restoreCurrentStep(startIdx);
            log.info("[REACT-LOOP] agentId={} resume plan from step {}/{}",
                    ctx.getAgentId(), startIdx, resumePlan.steps().size());
            return runStepLoop(ctx, resumePlan,
                    resumeTraces != null ? new ArrayList<>(resumeTraces) : new ArrayList<>(), startIdx);
        }

        List<StepTrace> stepTraces = new ArrayList<>();
        WorkingMemory wm = null;

        try {
            if (memoryBridge != null) {
                wm = memoryBridge.beforeTask(ctx);
                if (wm != null) {
                    ctx.putVariable("agent.workingMemory", wm);
                }
            }

            // ── RAG 上下文注入（Plan 阶段前，增强 Agent 规划） ──
            // Phase 4: 使用 RAGTriggerDecisionEngine 判断是否需要 RAG
            if (ragBridge != null && ragBridge.isAvailable()) {
                String intent = ctx.getTask().context() != null
                        ? (String) ctx.getTask().context().get("intent") : null;
                if (ragTrigger == null || ragTrigger.shouldTrigger(ctx.getTask().goal(), intent, ctx)) {
                    // 一旦决定走 RAG，就默认按“证据不足”处理；只有完整快照返回后才提升为 sufficient。
                    // 这样检索异常/超时不会静默退化成模型凭内部知识作答。
                    ctx.putVariable("agent.ragTriggered", true);
                    ctx.putVariable("agent.ragEvidenceSufficient", false);
                    // 提取 userId 用于多租户知识库隔离（只检索自己的 + 公共知识库）
                    Long ragUserId = null;
                    Object uidObj = ctx.getVariable("userId");
                    if (uidObj instanceof Long l) ragUserId = l;
                    else if (uidObj instanceof Number n) ragUserId = n.longValue();

                    // 单次检索快照：上下文、前端引用、trace 必须来自同一批结果。
                    try {
                        RagRetrievalSnapshot snapshot = ragBridge.retrieveSnapshot(
                                ctx.getTask().goal(), ragUserId);
                        if (snapshot == null) {
                            snapshot = RagRetrievalSnapshot.empty("", ctx.getTask().goal(),
                                    "bridge_returned_null", 0);
                        }
                        ctx.putVariable("agent.ragSnapshot", snapshot);
                        ctx.putVariable("agent.ragEvidenceSufficient", snapshot.evidenceSufficient());
                        if (!snapshot.traceId().isBlank()) {
                            ctx.putVariable("agent.ragTraceId", snapshot.traceId());
                        }
                        if (!snapshot.context().isBlank()) {
                            ctx.putVariable("agent.ragContext", snapshot.context());
                            log.info("[REACT-LOOP] agentId={} RAG 上下文注入, traceId={}, contextLen={}",
                                    ctx.getAgentId(), snapshot.traceId(), snapshot.context().length());
                        }

                        var ragDocs = snapshot.documents();
                        if (ragDocs != null && !ragDocs.isEmpty()) {
                            ctx.putVariable("agent.ragDocuments", ragDocs);
                            // 序列化文档引用为 JSON 数组，供前端 "引用资料" 面板展示
                            String refsJson = serializeRagReferences(ragDocs);
                            AgentEvent ragRefEvent = AgentEvent.of(
                                    AgentEventType.RAG_REFERENCES, 0, refsJson);
                            ctx.recordEvent(ragRefEvent);
                            fireProgress(ctx, ragRefEvent);
                            log.info("[REACT-LOOP] agentId={} RAG 引用文档 {} 篇",
                                    ctx.getAgentId(), ragDocs.size());
                        }
                    } catch (Exception e) {
                        log.debug("[REACT-LOOP] RAG 快照检索失败（不阻塞）: {}", e.getMessage());
                    }
                } else {
                    log.info("[REACT-LOOP] agentId={} RAG skipped by trigger decision", ctx.getAgentId());
                }
            }

            // ── 历史记忆召回（利用 Episodic 记忆增强决策） ──
            if (memoryBridge != null) {
                try {
                    var recallResult = memoryBridge.recall(ctx.getTask().goal(), ctx, 5);
                    if (recallResult != null) {
                        ctx.putVariable("agent.recallResult", recallResult);
                        log.info("[REACT-LOOP] agentId={} 历史记忆召回完成", ctx.getAgentId());

                        // 在线记忆召回质量采集（异步，失败不阻塞）
                        if (onlineQualityRecorder != null) {
                            try {
                                Long recallUserId = null;
                                Object uidObj = ctx.getVariable("userId");
                                if (uidObj instanceof Long l) recallUserId = l;
                                else if (uidObj instanceof Number n) recallUserId = n.longValue();
                                onlineQualityRecorder.recordMemoryRecall(
                                        recallUserId, ctx.getTask().goal(), recallResult);
                            } catch (Exception e) {
                                log.debug("[REACT-LOOP] 记忆召回指标采集失败（不阻塞）: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[REACT-LOOP] 记忆召回失败（不阻塞）: {}", e.getMessage());
                }
            }

            // Phase 1: Plan
            if (!ctx.transitionTo(AgentState.PLANNING)) {
                return AgentResult.fail("failed to transition to PLANNING", stepTraces, ctx.getCurrentStep());
            }

            Plan plan = planner.plan(ctx);
            AgentEvent planCompleteEvent = AgentEvent.of(AgentEventType.PLAN_COMPLETE, 0,
                    "steps=" + plan.steps().size());
            ctx.recordEvent(planCompleteEvent);
            fireProgress(ctx, planCompleteEvent);

            if (plan.isError()) {
                ctx.forceTransitionTo(AgentState.FAILED);
                return AgentResult.fail(plan.error(), stepTraces, ctx.getCurrentStep());
            }

            if (plan.isDirectResult()) {
                String finalResult = plan.result();
                // Planner 的 direct 分支原先会绕过 Executor，导致 RAG 最终答案没有经过证据门禁。
                // 有 RAG 证据的自然语言答案统一再走一次最终生成/校验；结构化契约保持原语义。
                @SuppressWarnings("unchecked")
                List<RetrievedDocument> ragDocuments =
                        ctx.getVariable("agent.ragDocuments", List.class);
                if (ragDocuments != null && !ragDocuments.isEmpty()
                        && !ctx.getConfig().jsonResponse()
                        && ctx.getConfig().outputContract() == null) {
                    ctx.transitionTo(AgentState.ACTING);
                    ctx.incrementStep();
                    ctx.putVariable("agent.finalStep", true);
                    Step groundedFinalStep = Step.of("grounded-direct", ctx.getTask().goal());
                    StepResult grounded = executor.execute(groundedFinalStep, ctx);
                    if (!grounded.success()) {
                        ctx.forceTransitionTo(AgentState.FAILED);
                        return AgentResult.fail(grounded.error(), stepTraces, ctx.getCurrentStep());
                    }
                    finalResult = grounded.output();
                }
                ctx.forceTransitionTo(AgentState.COMPLETED);
                if (memoryBridge != null) {
                    memoryBridge.afterTask(wm, ctx, finalResult);
                }
                return AgentResult.ok(finalResult, stepTraces, ctx.getCurrentStep(), ctx);
            }

            if (!plan.hasSteps()) {
                // Planner returned an empty plan (no steps, no direct result, no error).
                // Fall back to executing the original goal directly as a single step
                // so the user gets a useful response instead of a silent empty result.
                log.warn("[REACT-LOOP] agentId={} plan empty, falling back to direct execution", ctx.getAgentId());
                ctx.forceTransitionTo(AgentState.ACTING);
                ctx.incrementStep();
                ctx.putVariable("agent.finalStep", true);
                Step fallbackStep = new Step("direct-fallback", ctx.getTask().goal(),
                        null, Collections.emptyMap(), Collections.emptyList(), null);
                long stepStart = System.currentTimeMillis();
                StepResult sr = executor.execute(fallbackStep, ctx);
                long stepDuration = System.currentTimeMillis() - stepStart;
                stepTraces.add(new StepTrace(1, ctx.getTask().goal(), sr.output(),
                        "direct execution", "COMPLETE", 0, stepDuration));
                ctx.forceTransitionTo(AgentState.COMPLETED);
                AgentResult fallbackResult = AgentResult.ok(sr.output(), stepTraces, 1, ctx);
                if (memoryBridge != null) {
                    memoryBridge.afterTask(wm, ctx, sr.output());
                }
                return fallbackResult;
            }

            // Phase 2-4: Act → Observe → Reflect loop
            return runStepLoop(ctx, plan, stepTraces, 0);

        } catch (Exception e) {
            log.error("[REACT-LOOP] agentId={} error={}", ctx.getAgentId(), e.getMessage(), e);
            ctx.forceTransitionTo(AgentState.FAILED);
            if (memoryBridge != null) {
                memoryBridge.onTaskFailure(wm, ctx, e);
            }
            return AgentResult.fail(e.getMessage(), stepTraces, ctx.getCurrentStep());
        }
    }

    /**
     * 流式执行 ReAct Loop — 每个状态转换通过 Flux 实时推送。
     * <p>
     * 通过 ExecutionContextHolder 绑定执行上下文，确保 Gateway Filter Chain
     * 中的 RetryFilter（需要 ScopedValue 获取 traceId）能够正确工作。
     * <p>
     * 实时发射：通过 progressCallback 将执行过程中的事件实时推送到 FluxSink，
     * SSE 订阅者在 Agent 执行过程中即可收到 thinking/tool_call/step 事件。
     */
    public Flux<AgentEvent> enterStream(AgentContext ctx) {
        ExecutionContext execCtx = ctx.getExecCtx();
        return Flux.create(sink -> {
            // 将 FluxSink 存入上下文，供 executor 发射 TEXT_CHUNK
            ctx.putVariable("agent.fluxSink", sink);

            // 保存原始 progressCallback，叠加实时发射逻辑
            ProgressCallback originalCallback = this.progressCallback;
            this.progressCallback = (agentId, event) -> {
                sink.next(event);
                if (originalCallback != null) {
                    originalCallback.onEvent(agentId, event);
                }
            };

            try {
                ctx.markStarted();
                AgentEvent planStart = AgentEvent.of(AgentEventType.PLAN_START, 0, ctx.getTask().goal());
                ctx.recordEvent(planStart);
                sink.next(planStart);

                Callable<AgentResult> task = () -> enter(ctx);
                AgentResult result = execCtx != null
                        ? ExecutionContextHolder.runWith(execCtx, task)
                        : task.call();

                // enter() 内部已通过 fireProgress 实时发射了中间事件，
                // 此处只发射最终结果（不重复发射 trace）
                if (result.success()) {
                    try {
                        String resultJson = new ObjectMapper().writeValueAsString(result);
                        sink.next(AgentEvent.of(AgentEventType.AGENT_COMPLETE,
                                ctx.getCurrentStep(), resultJson));
                    } catch (Exception e) {
                        sink.next(AgentEvent.of(AgentEventType.AGENT_COMPLETE,
                                ctx.getCurrentStep(), result.result()));
                    }
                } else {
                    sink.next(AgentEvent.of(AgentEventType.AGENT_ERROR, ctx.getCurrentStep(), result.error()));
                }

                sink.complete();
            } catch (Exception e) {
                sink.next(AgentEvent.of(AgentEventType.AGENT_ERROR, ctx.getCurrentStep(), e.getMessage()));
                sink.complete();
            } finally {
                // 恢复原始 callback，避免影响后续任务
                this.progressCallback = originalCallback;
                // variables 底层是 ConcurrentHashMap，不接受 null 值，必须用 removeVariable
                ctx.removeVariable("agent.fluxSink");
            }
        });
    }

    /**
     * 取消正在执行的 Agent。
     */
    public void cancel(AgentContext ctx) {
        ctx.forceTransitionTo(AgentState.CANCELLED);
        log.info("[REACT-LOOP] agentId={} cancelled at step {}", ctx.getAgentId(), ctx.getCurrentStep());
    }

    /**
     * 从 HITL 审批点恢复执行。
     * <p>
     * {@link #enter(AgentContext)} 因 HITL 暂停后由外部调用，根据审批决定继续或跳过当前步骤。
     *
     * @param ctx        原始 AgentContext（包含已保存的 Plan / stepIndex / stepTraces）
     * @param decision   审批决定
     * @param modifierId 审批人 ID
     * @param feedback   审批反馈（REJECT/MODIFY 时填写原因）
     * @return 继续执行的结果
     */
    public AgentResult resume(AgentContext ctx, HitlDecision decision, String modifierId, String feedback) {
        Plan hitlPlan = ctx.getVariable("agent.hitlPlan", Plan.class);
        Integer hitlStepIdx = ctx.getVariable("agent.hitlStepIndex", Integer.class);

        if (hitlPlan == null || hitlStepIdx == null) {
            log.warn("[REACT-LOOP] resume: 未找到 HITL 暂停点, agentId={}", ctx.getAgentId());
            return AgentResult.fail("no hitl checkpoint found", List.of(), ctx.getCurrentStep());
        }

        String taskId = ctx.getExternalTaskId();
        if (taskId != null && hitlStore != null) {
            hitlStore.remove(taskId);
        }

        switch (decision) {
            case APPROVE -> {
                ctx.transitionTo(AgentState.APPROVED);
                log.info("[REACT-LOOP] agentId={} HITL APPROVED by {}, resuming step {}",
                        ctx.getAgentId(), modifierId, hitlStepIdx);
                // Plan/stepIndex 保留在 ctx 变量中，enter() 检测后从该步骤继续
            }
            case REJECT -> {
                ctx.transitionTo(AgentState.REJECTED);
                ctx.putVariable("agent.hitlFeedback", feedback != null ? feedback : "rejected by " + modifierId);
                // 跳过当前步骤：stepIndex + 1
                ctx.putVariable("agent.hitlStepIndex", hitlStepIdx + 1);
                log.info("[REACT-LOOP] agentId={} HITL REJECTED by {}, skipping step {}",
                        ctx.getAgentId(), modifierId, hitlStepIdx);
            }
            case MODIFY -> {
                ctx.transitionTo(AgentState.APPROVED);
                ctx.putVariable("agent.hitlFeedback", feedback != null ? feedback : "modified by " + modifierId);
                ctx.putVariable("agent.hitlReExecute", true);
                // 重新执行当前步骤
                log.info("[REACT-LOOP] agentId={} HITL MODIFY by {}, re-executing step {}",
                        ctx.getAgentId(), modifierId, hitlStepIdx);
            }
        }

        return enter(ctx);
    }

    /**
     * 执行 Plan 的步骤循环（Act → Observe → Reflect）。
     * <p>
     * 从 {@code startIndex} 开始执行，用于支持 HITL 恢复（原 enter 从 0 开始，resume 从 checkPoint 开始）。
     */
    private AgentResult runStepLoop(AgentContext ctx, Plan plan, List<StepTrace> stepTraces, int startIndex) {
        WorkingMemory wm = ctx.getVariable("agent.workingMemory", WorkingMemory.class);
        List<Step> steps = plan.steps();
        int stepIndex = startIndex;
        int consecutiveToolErrors = 0;
        // 记录上一次真正执行过的步骤下标，用于区分「新步骤」与「同一步骤重试」。
        // 原实现每轮循环都无条件 resetRetryCount()，而 RETRY 决策不推进 stepIndex，
        // 导致重试计数每轮被清零、hasExceededRetries() 恒为 false，同一步骤无限重试
        // （只能靠 maxSteps / timeout 兜底）。现在只在换步骤时清零。
        int lastExecutedStepIndex = -1;

        while (stepIndex < steps.size()) {
            if (ctx.isTerminal()) {
                break;
            }

            if (ctx.isTimedOut()) {
                log.warn("[REACT-LOOP] agentId={} TIMEOUT after {}, preserving {} traces",
                        ctx.getAgentId(), ctx.elapsed(), stepTraces.size());
                ctx.forceTransitionTo(AgentState.TIMEOUT);
                return AgentResult.timeout(ctx.getCurrentStep(), stepTraces, ctx.getConfig().timeout());
            }

            if (ctx.hasExceededMaxSteps()) {
                log.warn("[REACT-LOOP] agentId={} maxSteps reached: {}", ctx.getAgentId(), ctx.getCurrentStep());
                ctx.forceTransitionTo(AgentState.COMPLETED);
                break;
            }

            Step step = steps.get(stepIndex);
            ctx.putVariable("agent.finalStep", stepIndex == steps.size() - 1);

            if (memoryBridge != null && memoryBridge.getConfig().resumePointInterval() > 0
                    && ctx.getCurrentStep() > 0
                    && ctx.getCurrentStep() % memoryBridge.getConfig().resumePointInterval() == 0) {
                memoryBridge.createResumePoint(wm, "step_" + ctx.getCurrentStep());
            }

            // Act
            if (!ctx.transitionTo(AgentState.ACTING)) {
                return AgentResult.fail("failed to transition to ACTING", stepTraces, ctx.getCurrentStep());
            }

            ctx.incrementStep();
            if (stepIndex != lastExecutedStepIndex) {
                ctx.resetRetryCount();
                lastExecutedStepIndex = stepIndex;
            }
            String stepLabel = (step.description() != null && !step.description().isBlank())
                    ? step.description() : step.id();
            AgentEvent stepStartEvent = AgentEvent.of(AgentEventType.STEP_START, ctx.getCurrentStep(), stepLabel);
            ctx.recordEvent(stepStartEvent);
            fireProgress(ctx, stepStartEvent);

            // ── HITL 检查：执行高危操作前暂停等待审批 ──
            if (ctx.getConfig().enableHitl() && hitlPolicy != null && hitlStore != null) {
                HitlCheckpoint checkpoint = hitlPolicy.evaluate(step, ctx);
                if (checkpoint != null) {
                    hitlStore.save(checkpoint);
                    ctx.putVariable("agent.hitlPlan", plan);
                    ctx.putVariable("agent.hitlStepIndex", stepIndex);
                    ctx.putVariable("agent.hitlStepTraces", new ArrayList<>(stepTraces));
                    ctx.transitionTo(AgentState.AWAITING_APPROVAL);
                    // [UI 优化] HITL 事件携带完整风险上下文（toolName/toolParams/stepDescription/riskLevel），
                    // 前端审批卡据此展示"为什么需要审批"，用户有依据做决定。
                    String hitlData = buildHitlPayload(checkpoint, step);
                    AgentEvent hitlEvent = AgentEvent.of(AgentEventType.HITL_PENDING,
                            ctx.getCurrentStep(), hitlData);
                    ctx.recordEvent(hitlEvent);
                    fireProgress(ctx, hitlEvent);
                    log.info("[REACT-LOOP] agentId={} HITL triggered, taskId={} action={}",
                            ctx.getAgentId(), checkpoint.taskId(), checkpoint.action());
                    return AgentResult.waiting(checkpoint.taskId(), ctx.getCurrentStep(), stepTraces);
                }
            }

            long stepStart = System.currentTimeMillis();
            StepResult stepResult;
            try {
                stepResult = executor.execute(step, ctx);
                consecutiveToolErrors = 0;
            } catch (Exception toolEx) {
                // 工具执行异常不中断整个 ReAct 循环 — 将错误作为 observation 反馈给 LLM
                log.warn("[REACT-LOOP] agentId={} step={} tool execution error: {}",
                        ctx.getAgentId(), step.id(), toolEx.getMessage());
                long failDuration = System.currentTimeMillis() - stepStart;
                stepResult = StepResult.fail("工具执行失败: " + toolEx.getMessage(), failDuration);
                // 发射错误事件供前端展示（不中断流）
                AgentEvent toolErrorEvent = AgentEvent.of(AgentEventType.OBSERVE_RESULT,
                        ctx.getCurrentStep(), "TOOL_ERROR: " + toolEx.getMessage());
                ctx.recordEvent(toolErrorEvent);
                fireProgress(ctx, toolErrorEvent);

                StepTrace errorTrace = new StepTrace(ctx.getCurrentStep(), step.description(),
                        "ERROR: " + toolEx.getMessage(), "tool execution failed", "RETRY",
                        ctx.getRetryCount(), failDuration);
                stepTraces.add(errorTrace);

                // 超过重试次数则跳过该步骤继续执行后续
                consecutiveToolErrors++;
                if (consecutiveToolErrors >= ctx.getConfig().maxRetriesPerStep()) {
                    log.warn("[REACT-LOOP] agentId={} step={} retry limit exceeded after error, failing",
                            ctx.getAgentId(), step.id());
                    ctx.forceTransitionTo(AgentState.FAILED);
                    return AgentResult.fail("step execution failed after retries: " + toolEx.getMessage(),
                            stepTraces, ctx.getCurrentStep());
                } else {
                    ctx.incrementRetryCount();
                }
                continue;
            }
            long stepDuration = System.currentTimeMillis() - stepStart;

            if (memoryBridge != null) {
                memoryBridge.recordStep(wm, step, stepResult, ctx);
            }

            // Observe
            if (!ctx.transitionTo(AgentState.OBSERVING)) {
                return AgentResult.fail("failed to transition to OBSERVING", stepTraces, ctx.getCurrentStep());
            }

            Observation observation = observer.observe(stepResult, ctx);
            AgentEvent observeEvent = AgentEvent.of(AgentEventType.OBSERVE_RESULT, ctx.getCurrentStep(),
                    observation.status().name());
            ctx.recordEvent(observeEvent);
            fireProgress(ctx, observeEvent);

            // reportPlan 工具：额外发射 PLAN_UPDATE 事件供前端渲染语义进度
            if ("reportPlan".equals(step.tool()) && stepResult.success() && step.toolParams() != null) {
                try {
                    Object stages = step.toolParams().get("stages");
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    // 组装事件体：保留 eventType/data，附带阶段级进度元数据（对齐 docs/agent-frontend-rework.md §3.2）
                    java.util.LinkedHashMap<String, Object> planData = new java.util.LinkedHashMap<>();
                    planData.put("eventType", "PLAN_UPDATE");
                    planData.put("data", stages);
                    if (stages instanceof java.util.List<?> list) {
                        planData.put("stageTotal", list.size());
                        for (int i = 0; i < list.size(); i++) {
                            Object item = list.get(i);
                            if (!(item instanceof java.util.Map<?, ?> m)) {
                                continue;
                            }
                            if ("active".equalsIgnoreCase(String.valueOf(m.get("status")))) {
                                planData.put("stageIndex", i + 1);
                                planData.put("percent", Math.max(1, (i + 1) * 100 / Math.max(1, list.size())));
                                if (m.get("name") != null) {
                                    planData.put("stageLabel", String.valueOf(m.get("name")));
                                }
                                break;
                            }
                        }
                    }
                    AgentEvent planEvent = AgentEvent.of(AgentEventType.STEP_START, ctx.getCurrentStep(),
                            mapper.writeValueAsString(planData));
                    fireProgress(ctx, planEvent);
                } catch (Exception planEx) {
                    log.debug("[ReAct] PLAN_UPDATE emit failed: {}", planEx.getMessage());
                }
            }

            if (memoryBridge != null) {
                memoryBridge.recordObservation(wm, observation, ctx);
            }

            // Reflect
            if (!ctx.transitionTo(AgentState.REFLECTING)) {
                return AgentResult.fail("failed to transition to REFLECTING", stepTraces, ctx.getCurrentStep());
            }

            Decision decision = reflector.reflect(observation, ctx);
            AgentEvent reflectEvent = AgentEvent.of(AgentEventType.REFLECT_DECISION, ctx.getCurrentStep(),
                    decision.name());
            ctx.recordEvent(reflectEvent);
            fireProgress(ctx, reflectEvent);

            if (memoryBridge != null) {
                memoryBridge.recordDecision(wm, decision, ctx);
            }

            StepTrace trace = new StepTrace(ctx.getCurrentStep(), step.description(),
                    stepResult.output(), observation.summary(), decision.name(),
                    ctx.getRetryCount(), stepDuration);
            stepTraces.add(trace);

            int nextStepIndex = stepIndex;
            if (decision == Decision.CONTINUE) {
                nextStepIndex = stepIndex + 1;
            } else if (decision == Decision.RETRY && ctx.hasExceededRetries()) {
                nextStepIndex = stepIndex + 1;
            } else if (decision == Decision.COMPLETE) {
                nextStepIndex = steps.size();
            }
            // 将可恢复的计划、下一步骤和轨迹交给 checkpoint 适配层序列化。
            ctx.putVariable("agent.checkpointPlan", plan);
            ctx.putVariable("agent.checkpointNextStepIndex", nextStepIndex);
            if (step.id() != null) {
                ctx.putVariable("agent.checkpointStepId", step.id());
            } else {
                ctx.removeVariable("agent.checkpointStepId");
            }
            if (stepResult.output() != null) {
                ctx.putVariable("agent.checkpointStepOutput", stepResult.output());
            } else {
                ctx.removeVariable("agent.checkpointStepOutput");
            }
            ctx.putVariable("agent.checkpointStepDecision", decision.name());
            ctx.putVariable("agent.checkpointStepTraces", new ArrayList<>(stepTraces));

            if (checkpointCallback != null) {
                try {
                    String checkpointTaskId = ctx.getVariable("checkpointTaskId", String.class);
                    if (checkpointTaskId == null || checkpointTaskId.isBlank()) {
                        checkpointTaskId = ctx.getExternalTaskId();
                    }
                    if (checkpointTaskId == null || checkpointTaskId.isBlank()) {
                        checkpointTaskId = ctx.getAgentId();
                    }
                    checkpointCallback.onStepComplete(
                            checkpointTaskId,
                            ctx.getCurrentStep(), ctx);
                } catch (Exception e) {
                    log.debug("[REACT-LOOP] checkpoint save failed (non-fatal): {}", e.getMessage());
                }
            }

            switch (decision) {
                case CONTINUE -> stepIndex++;
                case RETRY -> {
                    // 先累计重试次数，再判断是否超限，否则 hasExceededRetries() 永远为 false
                    ctx.incrementRetryCount();
                    if (ctx.hasExceededRetries()) {
                        log.warn("[REACT-LOOP] agentId={} step={} retry limit exceeded ({}), continuing",
                                ctx.getAgentId(), step.id(), ctx.getRetryCount());
                        stepIndex++;
                    }
                }
                case COMPLETE -> {
                    ctx.forceTransitionTo(AgentState.COMPLETED);
                    AgentResult completeResult = AgentResult.ok(buildFinalResult(ctx, stepTraces),
                            stepTraces, ctx.getCurrentStep(), ctx);
                    if (memoryBridge != null) {
                        memoryBridge.afterTask(wm, ctx, completeResult.result());
                    }
                    return completeResult;
                }
            }
        }

        if (!ctx.isTerminal()) {
            ctx.forceTransitionTo(AgentState.COMPLETED);
        }
        AgentResult finalResult = AgentResult.ok(buildFinalResult(ctx, stepTraces),
                stepTraces, ctx.getCurrentStep(), ctx);
        if (memoryBridge != null) {
            memoryBridge.afterTask(wm, ctx, finalResult.result());
        }
        return finalResult;
    }

    private void fireProgress(AgentContext ctx, AgentEvent event) {
        if (progressCallback != null) {
            try {
                progressCallback.onEvent(ctx.getAgentId(), event);
            } catch (Exception e) {
                log.debug("[REACT-LOOP] progress callback failed (non-fatal): {}", e.getMessage());
            }
        }
    }

    private String buildFinalResult(AgentContext ctx, List<StepTrace> stepTraces) {
        // 优先取最后一条非空 assistant 消息（跳过 tool/user 回显与空消息）
        List<String> messages = ctx.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            String msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            String text = msg.replaceFirst("^assistant:", "").trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        // 回退：从步骤轨迹聚合 markdown 摘要，避免最终结果为空
        if (stepTraces != null) {
            StringBuilder sb = new StringBuilder();
            for (StepTrace trace : stepTraces) {
                if (trace.result() == null || trace.result().isBlank()) {
                    continue;
                }
                sb.append("### 步骤 ").append(trace.stepIndex()).append("：")
                        .append(trace.action() == null ? "" : trace.action()).append("\n\n");
                sb.append(trace.result()).append("\n\n");
            }
            if (sb.length() > 0) {
                return sb.toString().trim();
            }
        }
        return "";
    }

    /**
     * 将 RAG 检索到的文档列表序列化为 JSON 数组，供前端 "引用资料" 面板渲染。
     *
     * <p>每条引用包含：docId、title、snippet（截断 100 字符）、score、source、metadata。
     */
    private static final ObjectMapper RAG_MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private String serializeRagReferences(List<RetrievedDocument> docs) {
        try {
            List<java.util.LinkedHashMap<String, Object>> refs = new java.util.ArrayList<>();
            for (RetrievedDocument doc : docs) {
                java.util.LinkedHashMap<String, Object> ref = new java.util.LinkedHashMap<>();
                ref.put("docId", doc.docId());
                ref.put("title", doc.title() != null ? doc.title() : "");
                String snippet = doc.content() != null
                        ? (doc.content().length() > 100 ? doc.content().substring(0, 100) + "..." : doc.content())
                        : "";
                ref.put("snippet", snippet);
                ref.put("score", doc.score());
                ref.put("source", doc.source() != null ? doc.source() : "rag");
                ref.put("metadata", doc.metadata() != null ? doc.metadata() : java.util.Map.of());
                refs.add(ref);
            }
            return RAG_MAPPER.writeValueAsString(refs);
        } catch (Exception e) {
            log.debug("[REACT-LOOP] RAG 引用序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * [UI 优化] 组装 HITL 审批事件的完整上下文 JSON，
     * 前端审批卡据此展示 toolName / toolParams / stepDescription / riskLevel。
     */
    private static String buildHitlPayload(HitlCheckpoint checkpoint,
                                           com.yin.ai.platform.agent.planner.Step step) {
        try {
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("action", checkpoint.action());
            payload.put("riskLevel", checkpoint.riskLevel() != null
                    ? checkpoint.riskLevel().name() : "HIGH");
            java.util.Map<String, Object> ctxMap = checkpoint.context();
            if (ctxMap != null) {
                payload.putAll(ctxMap);
            }
            if (step.tool() != null) {
                payload.put("toolName", step.tool());
            }
            if (step.description() != null) {
                payload.put("stepDescription", step.description());
            }
            return RAG_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return checkpoint.action();
        }
    }
}
