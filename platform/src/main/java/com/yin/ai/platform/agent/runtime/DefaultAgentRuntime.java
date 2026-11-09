package com.yin.ai.platform.agent.runtime;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentEvent;
import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.CheckpointCallback;
import com.yin.ai.platform.agent.ProgressCallback;
import com.yin.ai.platform.agent.ReActLoop;
import com.yin.ai.platform.rag.trigger.RAGTriggerDecisionEngine;
import com.yin.ai.platform.agent.executor.AgentExecutor;
import com.yin.ai.platform.agent.executor.NoOpAgentExecutor;
import com.yin.ai.platform.agent.hitl.HitlDecision;
import com.yin.ai.platform.observe.OnlineQualityRecorder;
import com.yin.ai.platform.agent.hitl.HitlPolicy;
import com.yin.ai.platform.agent.hitl.HitlStore;
import com.yin.ai.platform.agent.memory.AgentMemoryBridge;
import com.yin.ai.platform.agent.rag.AgentRagBridge;
import com.yin.ai.platform.agent.observer.Observer;
import com.yin.ai.platform.agent.observer.NoOpObserver;
import com.yin.ai.platform.agent.planner.Planner;
import com.yin.ai.platform.agent.planner.NoOpPlanner;
import com.yin.ai.platform.agent.reflector.Reflector;
import com.yin.ai.platform.agent.reflector.NoOpReflector;
import com.yin.ai.platform.agent.strategy.ComplexityAssessment;
import com.yin.ai.platform.agent.strategy.ComplexityConfig;
import com.yin.ai.platform.agent.strategy.ComplexityAnalyzer;
import com.yin.ai.platform.agent.strategy.ExecutionStrategy;
import com.yin.ai.platform.agent.strategy.StrategyRouter;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionEngine;
import com.yin.ai.platform.core.runtime.execution.ExecutionOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentRuntime 默认实现。
 * <p>
 * 依赖:
 * <ul>
 *   <li>{@link ExecutionEngine} — 线程管理 + 超时控制</li>
 *   <li>{@link ReActLoop} — 核心状态机编排器</li>
 *   <li>{@link Planner} — 任务规划 (D1: NoOp, D2: Template/LLM)</li>
 *   <li>{@link AgentExecutor} — 步骤执行 (D1: NoOp, D3: 真实实现)</li>
 *   <li>{@link Observer} — 结果观察 (D1: NoOp, D4: 真实实现)</li>
 *   <li>{@link Reflector} — 反思决策 (D1: NoOp, D4: 真实实现)</li>
 * </ul>
 */
public class DefaultAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);

    private final ExecutionEngine engine;
    private final ReActLoop loop;
    private final Planner planner;
    private final AgentExecutor executor;
    private final Observer observer;
    private final Reflector reflector;
    private final SubAgentManager subAgentManager;
    private final StrategyRouter strategyRouter;
    private final Map<String, AgentContext> activeAgents = new ConcurrentHashMap<>();
    private OnlineQualityRecorder onlineQualityRecorder;
    /** [企业级缺口修复] RAG 触发决策引擎（可选）— 转发给内部 ReActLoop，使 shouldTrigger 生效 */
    private RAGTriggerDecisionEngine ragTrigger;

    public DefaultAgentRuntime(ExecutionEngine engine, Planner planner,
                               AgentExecutor executor, Observer observer, Reflector reflector) {
        this(engine, planner, executor, observer, reflector, null, null, null);
    }

    public DefaultAgentRuntime(ExecutionEngine engine, Planner planner,
                               AgentExecutor executor, Observer observer, Reflector reflector,
                               AgentMemoryBridge memoryBridge, AgentRagBridge ragBridge) {
        this(engine, planner, executor, observer, reflector, memoryBridge, ragBridge, null);
    }

    public DefaultAgentRuntime(ExecutionEngine engine, Planner planner,
                               AgentExecutor executor, Observer observer, Reflector reflector,
                               AgentMemoryBridge memoryBridge, AgentRagBridge ragBridge,
                               List<ComplexityAnalyzer> analyzers) {
        this.engine = Objects.requireNonNull(engine, "execution engine must not be null");
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.reflector = Objects.requireNonNull(reflector, "reflector must not be null");
        this.loop = new ReActLoop(planner, executor, observer, reflector);
        if (memoryBridge != null) {
            this.loop.setMemoryBridge(memoryBridge);
        }
        if (ragBridge != null) {
            this.loop.setRagBridge(ragBridge);
        }
        if (onlineQualityRecorder != null) {
            this.loop.setOnlineQualityRecorder(onlineQualityRecorder);
        }
        if (ragTrigger != null) {
            this.loop.setRagTrigger(ragTrigger);
        }
        this.subAgentManager = new SubAgentManager(this::runSubAgent);
        this.strategyRouter = new StrategyRouter(
                analyzers != null ? analyzers : List.of(),
                ComplexityConfig.defaults());
        warnIfPlaceholderDependencies();
    }

    /** 在线检索质量采集器（可选，转发给内部 ReActLoop）。 */
    public void setOnlineQualityRecorder(OnlineQualityRecorder recorder) {
        this.onlineQualityRecorder = recorder;
        if (this.loop != null) {
            this.loop.setOnlineQualityRecorder(recorder);
        }
    }

    /** [企业级缺口修复] RAG 触发决策引擎（可选，转发给内部 ReActLoop，使智能 RAG 决策生效）。 */
    public void setRagTrigger(RAGTriggerDecisionEngine trigger) {
        this.ragTrigger = trigger;
        if (this.loop != null) {
            this.loop.setRagTrigger(trigger);
        }
    }

    private void warnIfPlaceholderDependencies() {
        if (planner instanceof NoOpPlanner || executor instanceof NoOpAgentExecutor
                || observer instanceof NoOpObserver || reflector instanceof NoOpReflector) {
            log.warn("[AGENT-RUNTIME] placeholder NoOp dependency detected; production execution may be incomplete "
                    + "(planner={}, executor={}, observer={}, reflector={})",
                    planner.getClass().getSimpleName(), executor.getClass().getSimpleName(),
                    observer.getClass().getSimpleName(), reflector.getClass().getSimpleName());
        }
    }

    /**
     * 设置检查点回调 — 由 Spring 容器在构造后注入（wkc-ai-service 的 AutoConfiguration）。
     */
    public void setCheckpointCallback(CheckpointCallback callback) {
        this.loop.setCheckpointCallback(callback);
    }

    /**
     * 设置进度回调 — 在 Agent 执行过程中实时推送进度事件到前端。
     */
    public void setProgressCallback(ProgressCallback callback) {
        this.loop.setProgressCallback(callback);
    }

    /**
     * 设置 HITL 策略 — 决定哪些操作需要人工审批。
     */
    public void setHitlPolicy(HitlPolicy policy) {
        this.loop.setHitlPolicy(policy);
    }

    /**
     * 设置 HITL 存储 — 持久化待审批检查点。
     */
    public void setHitlStore(HitlStore store) {
        this.loop.setHitlStore(store);
    }

    /**
     * 从 HITL 审批点恢复 Agent 执行。
     *
     * @param agentId   被暂停的 Agent ID
     * @param decision  审批决定
     * @param modifierId 审批人 ID
     * @param feedback  审批反馈
     * @return 继续执行的结果，若 agent 不存在返回 fail
     */
    public AgentResult resumeFromHitl(String agentId, HitlDecision decision, String modifierId, String feedback) {
        AgentContext ctx = activeAgents.get(agentId);
        if (ctx == null) {
            log.warn("[AGENT-RUNTIME] resumeFromHitl: agent not found, id={}", agentId);
            return AgentResult.fail("agent not found or already completed", List.of(), 0);
        }
        return loop.resume(ctx, decision, modifierId, feedback);
    }

    /**
     * 检查 Agent 是否处于等待审批状态。
     */
    public boolean isAwaitingApproval(String agentId) {
        AgentContext ctx = activeAgents.get(agentId);
        return ctx != null && ctx.getState() == com.yin.ai.platform.agent.AgentState.AWAITING_APPROVAL;
    }

    @Override
    public AgentResult execute(AgentTask task) {
        ExecutionContext execCtx = ExecutionContext.fromCurrentRequest().withOperation("agent:" + task.goal());
        AgentContext ctx = AgentContext.from(task, execCtx);
        activeAgents.put(ctx.getAgentId(), ctx);

        try {
            // 运行时策略决策：评估任务复杂度并存储供 MemoryBridge 等下游使用
            ComplexityAssessment assessment = strategyRouter.assess(task.goal(), ctx);
            ExecutionStrategy strategy = strategyRouter.route(task.goal(), ctx);
            ctx.putVariable("agent.executionStrategy", strategy);
            ctx.putVariable("agent.complexity", assessment);
            log.info("[AGENT-RUNTIME] agentId={} goal=\"{}\" strategy={} level={} confidence={}",
                    ctx.getAgentId(), task.goal(), strategy, assessment.level(), assessment.confidence());

            // 【企业级缺口修复】DIRECT_SKILL 快捷路径：简单任务单轮 LLM 调用，跳过 ReActLoop。
            // 双重保护防回归：
            //  ① 默认配置 SIMPLE→REACT_LOOP（ComplexityConfig.defaults）
            //  ② 仅当 strategyRouter 真正启用（config.enabled && analyzers 非空）且评估出 DIRECT_SKILL
            //     —— disabled/无 analyzer 时 assess 返回的"简单"是兜底值，不触发快捷路径
            if (strategy == ExecutionStrategy.DIRECT_SKILL && strategyRouter.isActive()) {
                return executeDirectSkill(ctx, execCtx);
            }

            var result = engine.execute(execCtx, () -> loop.enter(ctx),
                    ExecutionOptions.of("agent.execute", task.config().timeout()));

            if (result.cancelled()) {
                return AgentResult.cancelled(ctx.getCurrentStep());
            }
            if (!result.success()) {
                return AgentResult.fail(result.error() != null ? result.error().getMessage() : "unknown error",
                        List.of(), ctx.getCurrentStep());
            }
            return result.data();

        } finally {
            activeAgents.remove(ctx.getAgentId());
            log.info("[AGENT-RUNTIME] agentId={} completed state={} steps={} duration={}",
                    ctx.getAgentId(), ctx.getState(), ctx.getCurrentStep(), ctx.elapsed());
        }
    }

    /**
     * 【企业级缺口修复】DIRECT_SKILL 快捷路径 — 单步 LLM 生成，跳过 Plan→Act→Observe→Reflect 循环。
     * <p>
     * 适用：SIMPLE 复杂度任务（一句话问答、简单生成）。省一次完整 ReActLoop 的规划+多轮调用。
     * 结果包装为单步 AgentResult，与 ReActLoop 返回结构兼容（StepTrace 记录一步）。
     */
    private AgentResult executeDirectSkill(AgentContext ctx, ExecutionContext execCtx) {
        long start = System.currentTimeMillis();
        try {
            // 构造单步纯 LLM 任务（无工具调用）
            com.yin.ai.platform.agent.planner.Step step =
                    com.yin.ai.platform.agent.planner.Step.of("direct", ctx.getTask().goal());
            com.yin.ai.platform.agent.executor.StepResult result = executor.execute(step, ctx);

            long durationMs = System.currentTimeMillis() - start;
            if (result.success()) {
                List<com.yin.ai.platform.agent.StepTrace> trace = List.of(
                        com.yin.ai.platform.agent.StepTrace.of(0, "direct", result.output(), durationMs));
                log.info("[AGENT-RUNTIME] DIRECT_SKILL success agentId={} durationMs={}",
                        ctx.getAgentId(), durationMs);
                return com.yin.ai.platform.agent.AgentResult.ok(result.output(), trace, 1, ctx);
            }
            log.warn("[AGENT-RUNTIME] DIRECT_SKILL failed agentId={} err={}",
                    ctx.getAgentId(), result.error());
            return com.yin.ai.platform.agent.AgentResult.fail(result.error(), List.of(), 0);
        } catch (Exception e) {
            log.warn("[AGENT-RUNTIME] DIRECT_SKILL error agentId={} err={}",
                    ctx.getAgentId(), e.getMessage());
            return com.yin.ai.platform.agent.AgentResult.fail(e.getMessage(), List.of(), 0);
        }
    }

    @Override
    public Flux<AgentEvent> executeStream(AgentTask task) {
        ExecutionContext execCtx = ExecutionContext.fromCurrentRequest().withOperation("agent:" + task.goal());
        AgentContext ctx = AgentContext.from(task, execCtx);
        activeAgents.put(ctx.getAgentId(), ctx);

        // [追踪 P0-1b] 捕获调用线程（请求线程）的 OTel Context，异步执行期间恢复——
        // 否则 subscribeOn(boundedElastic) 切线程后 OTel Context 丢失，一次 agent 执行的
        // plan/execute/query_rewrite/rerank 各 span 变成独立 root trace（管理端追踪页面分散）。
        io.opentelemetry.context.Context otelContext = io.opentelemetry.context.Context.current();
        io.opentelemetry.api.trace.Span currentSpan = io.opentelemetry.api.trace.Span.current();
        final boolean hasTrace = currentSpan != null && currentSpan.getSpanContext().isValid();

        return loop.enterStream(ctx)
                .doFirst(() -> {
                    if (execCtx.securityContext() != null) {
                        SecurityContextHolder.setContext(execCtx.securityContext());
                    }
                    if (execCtx.mdcContext() != null) {
                        MDC.setContextMap(execCtx.mdcContext());
                    }
                    if (hasTrace) {
                        otelScope.set(otelContext.makeCurrent());
                    }
                })
                .doOnTerminate(() -> {
                    activeAgents.remove(ctx.getAgentId());
                    SecurityContextHolder.clearContext();
                    MDC.clear();
                    io.opentelemetry.context.Scope scope = otelScope.get();
                    if (scope != null) {
                        scope.close();
                        otelScope.remove();
                    }
                });
    }

    /** 异步执行期间的 OTel Scope 容器（线程级）。 */
    private final ThreadLocal<io.opentelemetry.context.Scope> otelScope = new ThreadLocal<>();

    @Override
    public void cancel(String agentIdOrTaskId) {
        // 先按 agentId 精确查找
        AgentContext ctx = activeAgents.get(agentIdOrTaskId);
        if (ctx == null) {
            // 再按 externalTaskId（即外部 taskId）遍历查找
            ctx = activeAgents.values().stream()
                    .filter(c -> agentIdOrTaskId.equals(c.getExternalTaskId()))
                    .findFirst()
                    .orElse(null);
        }
        if (ctx != null) {
            log.info("[AGENT-RUNTIME] cancel agentId={} externalTaskId={}", ctx.getAgentId(), ctx.getExternalTaskId());
            loop.cancel(ctx);
        } else {
            log.warn("[AGENT-RUNTIME] cancel: 未找到活跃任务, id={}", agentIdOrTaskId);
        }
    }

    @Override
    public SubAgentResult delegate(AgentTask subTask, AgentContext parentCtx) {
        return subAgentManager.delegate(parentCtx, subTask);
    }

    /**
     * 执行 SubAgent 的 ReActLoop（由 SubAgentManager 回调）。
     * 包内可见，不暴露到 SPI。
     */
    AgentResult runSubAgent(AgentContext childCtx) {
        activeAgents.put(childCtx.getAgentId(), childCtx);
        try {
            return engine.execute(childCtx.getExecCtx(), () -> loop.enter(childCtx),
                    ExecutionOptions.of("subagent.execute", childCtx.getConfig().timeout()))
                    .data();
        } finally {
            activeAgents.remove(childCtx.getAgentId());
        }
    }
}
