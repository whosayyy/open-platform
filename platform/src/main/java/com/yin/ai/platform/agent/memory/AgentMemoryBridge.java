package com.yin.ai.platform.agent.memory;

import com.yin.ai.platform.memory.LegacyMemoryContextMapper;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.executor.StepResult;
import com.yin.ai.platform.agent.observer.Observation;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.reflector.Decision;
import com.yin.ai.platform.agent.strategy.ComplexityAssessment;
import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemorySnapshot;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.orchestration.MemoryAccessContext;
import com.yin.ai.platform.memory.orchestration.MemoryCandidate;
import com.yin.ai.platform.memory.orchestration.MemoryContextBundle;
import com.yin.ai.platform.memory.orchestration.MemoryOrchestrator;
import com.yin.ai.platform.memory.strategy.MemoryStrategySelector;
import com.yin.ai.platform.memory.strategy.MemoryTypeDecision;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent &#x2194; MemoryRuntime 桥接层。
 * <p>
 * 参考 {@code EngineMemoryBridge} 模式，提供 Agent Runtime 所需的完整记忆能力：
 * <ol>
 *   <li>任务执行前 → 创建 WorkingMemory（含 Plan 树）</li>
 *   <li>每步执行后 → 追加 Episodic Cell</li>
 *   <li>关键决策点 → 创建 ResumePoint（供故障恢复）</li>
 *   <li>任务完成后 → 写入最终结果</li>
 *   <li>任务失败时 → 保存 WorkingMemory + 创建错误 ResumePoint</li>
 *   <li>检索召回 → 跨 Episodic/Semantic 搜索历史记忆</li>
 * </ol>
 */
public class AgentMemoryBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryBridge.class);

    private final MemoryRuntime memoryRuntime;
    private final AgentMemoryConfig config;
    private final MemoryStrategySelector strategySelector;
    private final MemoryOrchestrator orchestrator;
    private boolean orchestratedRetrieval;

    public AgentMemoryBridge(MemoryRuntime memoryRuntime, AgentMemoryConfig config) {
        this(memoryRuntime, config, null);
    }

    public AgentMemoryBridge(MemoryRuntime memoryRuntime, AgentMemoryConfig config,
                             MemoryStrategySelector strategySelector) {
        this(memoryRuntime, config, strategySelector, null);
    }

    public AgentMemoryBridge(MemoryRuntime memoryRuntime, AgentMemoryConfig config,
                             MemoryStrategySelector strategySelector,
                             MemoryOrchestrator orchestrator) {
        this.memoryRuntime = memoryRuntime;
        this.config = config;
        this.strategySelector = strategySelector;
        this.orchestrator = orchestrator;
    }

    public AgentMemoryConfig getConfig() {
        return config;
    }

    // —— Lifecycle ——

    /** ReAct Loop 启动前：创建 WorkingMemory */
    public WorkingMemory beforeTask(AgentContext ctx) {
        MemoryTypeDecision decision = resolveMemoryDecision(ctx);
        if (!decision.loadWorkingMemory()) {
            log.debug("[AGENT-MEM] agentId={} workingMemory skipped: {}", ctx.getAgentId(), decision.reason());
            return null;
        }
        MemoryContext memCtx = toMemoryContext(ctx);
        WorkingMemory wm = memoryRuntime.getOrCreateWorkingMemory(memCtx);
        log.info("[AGENT-MEM] agentId={} workingMemory={} created reason={}",
                ctx.getAgentId(), wm.getId(), decision.reason());
        return wm;
    }

    /** 记录单个 Step 的执行结果到 Episodic Timeline */
    public MemoryCell recordStep(WorkingMemory wm, Step step, StepResult result,
                                  AgentContext ctx) {
        if (!config.episodicEnabled() || wm == null) {
            return null;
        }
        String content = "[step:" + ctx.getCurrentStep() + "] "
                + step.description() + " → "
                + (result.output() != null ? result.output() : "(no output)");
        return appendEpisodic(content, ctx);
    }

    /** 记录 Observation 到 Episodic Timeline */
    public MemoryCell recordObservation(WorkingMemory wm, Observation obs,
                                         AgentContext ctx) {
        if (!config.episodicEnabled() || wm == null) {
            return null;
        }
        return appendEpisodic("[observe:" + ctx.getCurrentStep() + "] "
                + obs.status().name() + ": " + obs.summary(), ctx);
    }

    /** 记录 Reflect 决策到 Episodic Timeline */
    public MemoryCell recordDecision(WorkingMemory wm, Decision decision,
                                      AgentContext ctx) {
        if (!config.episodicEnabled() || wm == null) {
            return null;
        }
        return appendEpisodic("[reflect:" + ctx.getCurrentStep() + "] "
                + decision.name(), ctx);
    }

    /** 在关键步骤前创建 ResumePoint */
    public ResumePoint createResumePoint(WorkingMemory wm, String label) {
        if (!config.workingMemoryEnabled() || wm == null) {
            return null;
        }
        return memoryRuntime.createResumePoint(wm.getId(), label);
    }

    /** 任务成功完成 → 写入 Episodic */
    public void afterTask(WorkingMemory wm, AgentContext ctx, String finalResult) {
        if (!config.episodicEnabled() || wm == null) {
            return;
        }
        appendEpisodic("[complete] " + finalResult, ctx);
        log.info("[AGENT-MEM] agentId={} task completed, episodic recorded", ctx.getAgentId());
    }

    /** 任务失败 — 保存 WorkingMemory + 创建恢复点 */
    public Optional<ResumePoint> onTaskFailure(WorkingMemory wm, AgentContext ctx,
                                                Throwable error) {
        if (!config.workingMemoryEnabled() || wm == null) {
            return Optional.empty();
        }
        memoryRuntime.saveWorkingMemory(wm);
        try {
            String label = "error_" + System.currentTimeMillis();
            ResumePoint point = memoryRuntime.createResumePoint(wm.getId(), label);
            log.info("[AGENT-MEM] agentId={} failure saved, resumePoint={}", ctx.getAgentId(), label);
            return Optional.of(point);
        } catch (Exception e) {
            log.warn("[AGENT-MEM] agentId={} failed to create resume point: {}", ctx.getAgentId(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    // —— Retrieval ——

    /** 回忆历史记忆（供 Agent LLM 上下文增强） */
    public QueryResult recall(String query, AgentContext ctx, int topK) {
        MemoryTypeDecision decision = resolveMemoryDecision(ctx);
        int effectiveTopK = decision.recallTopK() > 0 ? decision.recallTopK() : topK;

        List<MemoryType> types = new java.util.ArrayList<>();
        if (decision.loadEpisodic()) types.add(MemoryType.EPISODIC);
        if (decision.loadSemantic()) types.add(MemoryType.SEMANTIC);
        if (decision.loadProcedural()) types.add(MemoryType.PROCEDURAL);
        if (types.isEmpty()) types.add(MemoryType.EPISODIC); // fallback

        MemoryQuery memoryQuery = MemoryQuery.byText(query, effectiveTopK)
                .withTypeFilter(types.toArray(new MemoryType[0]));
        return retrieve(memoryQuery, ctx);
    }

    /** Mode-aware recall used by UnifiedAgentRuntime. */
    public QueryResult recall(String query, AgentContext ctx, int topK, MemoryType... types) {
        MemoryQuery memoryQuery = MemoryQuery.byText(query, topK);
        if (types != null && types.length > 0) {
            memoryQuery = memoryQuery.withTypeFilter(types);
        }
        return retrieve(memoryQuery, ctx);
    }

    /** Records one unified-runtime step into the episodic timeline. */
    public MemoryCell recordPatternStep(WorkingMemory wm, String stepId, String output, AgentContext ctx) {
        if (!config.episodicEnabled() || wm == null) return null;
        return appendEpisodic("[step:" + stepId + "] " + (output == null ? "(no output)" : output), ctx);
    }

    /** 读取最近的执行时间线 */
    public List<MemoryCell> readRecentTimeline(AgentContext ctx, int limit) {
        MemoryId episodicId = getEpisodicId(ctx);
        return memoryRuntime.readTimeline(episodicId, limit);
    }

    // —— Working Memory 操作 ——

    public void putVariable(WorkingMemory wm, String key, Object value) {
        if (wm != null) {
            wm.putVariable(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getVariable(WorkingMemory wm, String key, Class<T> type) {
        if (wm == null) {
            return Optional.empty();
        }
        return wm.getVariable(key, type);
    }

    public void saveWorkingMemory(WorkingMemory wm) {
        if (wm != null) {
            memoryRuntime.saveWorkingMemory(wm);
        }
    }

    public MemorySnapshot snapshot(WorkingMemory wm) {
        if (wm == null) {
            return null;
        }
        return memoryRuntime.snapshot(wm.getId());
    }

    public MemoryRuntime memoryRuntime() {
        return memoryRuntime;
    }

    // —— internal ——

    private MemoryTypeDecision resolveMemoryDecision(AgentContext ctx) {
        if (strategySelector != null) {
            try {
                ComplexityAssessment complexity = ctx.getVariable("agent.complexity",
                        ComplexityAssessment.class);
                return strategySelector.select(ctx, complexity);
            } catch (Exception e) {
                log.debug("[AGENT-MEM] strategySelector failed, falling back to config: {}", e.getMessage());
            }
        }
        // Fallback: use legacy AgentMemoryConfig flags
        return new MemoryTypeDecision(
                config.workingMemoryEnabled(), config.episodicEnabled(),
                config.semanticSearchEnabled(), false,
                config.defaultRecallTopK(),
                "legacy config fallback");
    }

    private MemoryCell appendEpisodic(String content, AgentContext ctx) {
        MemoryId episodicId = getEpisodicId(ctx);
        MemoryCell stored = memoryRuntime.append(episodicId, content, toMemoryContext(ctx));
        if (orchestrator != null) {
            try {
                orchestrator.remember(new MemoryCandidate(content, "task_experience",
                        stored != null ? stored.cellId().id() : episodicId.id(), null, null,
                        null, null, null, Instant.now(), Map.of("rawEpisodeStored", true)),
                        toAccessContext(ctx));
            } catch (RuntimeException exception) {
                log.debug("[AGENT-MEM] orchestrator shadow decision failed: {}", exception.getMessage());
            }
        }
        return stored;
    }

    private QueryResult retrieve(MemoryQuery query, AgentContext ctx) {
        if (orchestrator == null || !orchestratedRetrieval) {
            return memoryRuntime.retrieve(query, toMemoryContext(ctx));
        }
        MemoryContextBundle bundle = orchestrator.retrieve(query, toAccessContext(ctx));
        return QueryResult.of(bundle.memories(), bundle.memories().size(), Duration.ZERO);
    }

    public void setOrchestratedRetrieval(boolean orchestratedRetrieval) {
        this.orchestratedRetrieval = orchestratedRetrieval;
    }

    private MemoryAccessContext toAccessContext(AgentContext ctx) {
        MemoryAccessContext base = MemoryAccessContext.from(ctx.getExecCtx(), true);
        String sessionId = ctx.getSessionId() != null ? ctx.getSessionId() : base.sessionId();
        return new MemoryAccessContext(base.tenantId(), base.userId(), sessionId,
                ctx.getExternalTaskId(), base.workflowId(), ctx.getAgentId(),
                Integer.toString(ctx.getCurrentStep()), base.roles(), base.permissions(), base.traceId());
    }

    private MemoryId getEpisodicId(AgentContext ctx) {
        Long userId = ctx.getExecCtx().userId();
        String ownerKey = userId != null ? userId.toString() : "system";
        return MemoryId.of(ctx.getAgentId(), MemoryType.EPISODIC, ownerKey);
    }

    private MemoryContext toMemoryContext(AgentContext ctx) {
        return LegacyMemoryContextMapper.from(ctx.getExecCtx())
                .withAgentId(ctx.getAgentId());
    }
}
