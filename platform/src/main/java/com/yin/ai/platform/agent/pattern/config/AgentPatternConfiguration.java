package com.yin.ai.platform.agent.pattern.config;

import com.yin.ai.platform.agent.ReActLoop;
import com.yin.ai.platform.adapter.runtime.persistence.RedisContextStore;
import com.yin.ai.platform.adapter.runtime.persistence.RedisTaskEngine;
import com.yin.ai.platform.adapter.runtime.persistence.RedisTaskTokenAccountant;
import com.yin.ai.platform.agent.memory.AgentMemoryBridge;
import com.yin.ai.platform.agent.memory.AgentMemoryLifecycle;
import com.yin.ai.platform.agent.policy.AgentStepPolicy;
import com.yin.ai.platform.observe.RouterFirstMonitor;
import com.yin.ai.platform.agent.memory.consolidation.DefaultMemoryConsolidationEngine;
import com.yin.ai.platform.agent.memory.consolidation.MemoryConsolidationEngine;
import com.yin.ai.platform.agent.memory.consolidation.MemoryConsolidationSubscriber;
import com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategyResolver;
import com.yin.ai.platform.agent.observe.StepExecutionGovernance;
import com.yin.ai.platform.agent.observe.AgentRuntimeMetrics;
import com.yin.ai.platform.agent.observe.StepMetrics;
import com.yin.ai.platform.agent.observe.StepTracer;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.pattern.*;
import com.yin.ai.platform.agent.task.*;
import com.yin.ai.platform.agent.team.AgentTeamCoordinator;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.rag.grounding.ClaimEvidenceQualityJudge;
import com.yin.ai.platform.structured.StructuredClient;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent Pattern 自动配置 — 注册统一 Agent Runtime 所需的全部 Bean。
 * <p>
 * 条件注册：所有 Bean 均标注 @ConditionalOnMissingBean，
 * 业务层可通过自定义 Bean 覆盖平台默认实现。
 */
@Configuration
@ConditionalOnBean(RedissonClient.class)
public class AgentPatternConfiguration {

    // === 基础设施 ===

    @Bean
    @ConditionalOnMissingBean(name = "contextStoreExecutor")
    public ExecutorService contextStoreExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ctx-store-async");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    @ConditionalOnMissingBean(ContextStore.class)
    public ContextStore contextStore(RedissonClient redisson,
                                      ExecutorService contextStoreExecutor) {
        return new RedisContextStore(redisson, contextStoreExecutor);
    }

    // === 任务引擎 ===

    @Bean
    @ConditionalOnMissingBean(TaskEngine.class)
    public TaskEngine taskEngine(RedissonClient redisson, EventPublisher eventPublisher) {
        return new RedisTaskEngine(redisson, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean(name = "agentDagExecutor")
    public ExecutorService agentDagExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "agent-dag");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    @ConditionalOnMissingBean(TaskTokenAccountant.class)
    public TaskTokenAccountant taskTokenAccountant(RedissonClient redisson) {
        return new RedisTaskTokenAccountant(redisson);
    }

    @Bean
    @ConditionalOnMissingBean(StepMetrics.class)
    public StepMetrics stepMetrics(MeterRegistry meterRegistry) {
        return new StepMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(StepTracer.class)
    public StepTracer stepTracer(ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetry telemetry = openTelemetry.getIfAvailable(OpenTelemetry::noop);
        return new StepTracer(telemetry.getTracer("wkc-ai-platform.agent"));
    }

    @Bean
    @ConditionalOnMissingBean(StepExecutionGovernance.class)
    public StepExecutionGovernance stepExecutionGovernance(TaskTokenAccountant accountant,
                                                            TaskEngine engine,
                                                            StepMetrics metrics,
                                                            StepTracer tracer,
                                                            ObjectProvider<AgentMemoryLifecycle> memoryLifecycle) {
        return new StepExecutionGovernance(accountant, engine, metrics, tracer,
                memoryLifecycle.getIfAvailable());
    }

    // === 路由器 ===

    @Bean
    @ConditionalOnMissingBean(RuleModeClassifier.class)
    public RuleModeClassifier ruleModeClassifier() {
        return new RuleModeClassifier();
    }

    @Bean
    @ConditionalOnMissingBean(LlmModeClassifier.class)
    public LlmModeClassifier llmModeClassifier(StructuredClient structuredClient) {
        return new LlmModeClassifier(structuredClient);
    }

    @Bean
    @ConditionalOnMissingBean(AgentRouter.class)
    public AgentRouter agentRouter(List<ModeClassifier> classifiers) {
        return new DefaultAgentRouter(classifiers);
    }

    // === 共享基础设施 ===

    /**
     * 【BUG 修复】@ConditionalOnMissingBean 从 name 判断改为按类型判断。
     * 原实现按 bean 名 "llmStepExecutor" 判断：workflow 包有一个 @Component LlmStepExecutor
     * （bean 名恰好同名），导致本 Bean 被误跳过 → List&lt;StepExecutor&gt; 里没有 llm 执行器，
     * dispatch("llm") 匹配不到，落到 fallback（日志 15 次 no executor for 'llm'）。
     * 按类型判断后：workflow 版是不同类（workflow.executor.LlmStepExecutor），不再阻断。
     * Bean 方法名用 patternLlmStepExecutor：避免与 @Component LlmStepExecutor
     * 的默认 bean 名 "llmStepExecutor" 冲突（overriding 被禁用时注册失败）。
     */
    @Bean
    @ConditionalOnMissingBean(com.yin.ai.platform.agent.pattern.executor.LlmStepExecutor.class)
    public com.yin.ai.platform.agent.pattern.executor.LlmStepExecutor patternLlmStepExecutor(
            GatewayTextClient gatewayTextClient) {
        return new com.yin.ai.platform.agent.pattern.executor.LlmStepExecutor(gatewayTextClient);
    }

    /**
     * 【BUG 修复】同上：service adapter 包的 @Component ViewArtifactStepExecutor 撞名，
     * 导致本 Bean 被跳过 → LLM 规划的 view_artifact 步骤走的是依赖 minio artifactKey 的
     * service 版，而 pattern 版从 ctx 变量 step.{dep}.output 读产物（PlanExecute L152 已写）。
     * 按类型判断后 pattern 版注册成功；dispatcher 按 priority 100 优先选 pattern 版。
     * Bean 方法名用 patternViewArtifactStepExecutor：避免与 service adapter 包的
     * @Component ViewArtifactStepExecutor 默认 bean 名 "viewArtifactStepExecutor" 冲突。
     */
    @Bean
    @ConditionalOnMissingBean(com.yin.ai.platform.agent.pattern.executor.ViewArtifactStepExecutor.class)
    public com.yin.ai.platform.agent.pattern.executor.ViewArtifactStepExecutor patternViewArtifactStepExecutor() {
        return new com.yin.ai.platform.agent.pattern.executor.ViewArtifactStepExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher.class)
    public com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher stepExecutorDispatcher(
            List<com.yin.ai.platform.agent.pattern.executor.StepExecutor> executors,
            ObjectProvider<StepExecutionGovernance> governance) {
        return new com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher(
                executors, governance.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(com.yin.ai.platform.agent.pattern.executor.ParallelStepExecutor.class)
    public com.yin.ai.platform.agent.pattern.executor.ParallelStepExecutor parallelStepExecutor(
            com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher dispatcher,
            com.yin.ai.platform.agent.pattern.context.ContextManager contextManager,
            @Qualifier("agentDagExecutor") ExecutorService executor) {
        return new com.yin.ai.platform.agent.pattern.executor.ParallelStepExecutor(
                dispatcher, contextManager, executor);
    }

    @Bean
    @ConditionalOnMissingBean(com.yin.ai.platform.agent.pattern.context.ContextManager.class)
    public com.yin.ai.platform.agent.pattern.context.ContextManager contextManager(
            GatewayTextClient gatewayTextClient,
            com.yin.ai.platform.core.memory.api.MemoryRuntime memoryRuntime,
            ObjectProvider<com.yin.ai.platform.observe.OnlineQualityRecorder> onlineQualityRecorder,
            ObjectProvider<com.yin.ai.platform.memory.context.MemoryContextProvider> memoryContextProvider) {
        return new com.yin.ai.platform.agent.pattern.context.ContextManager(
                gatewayTextClient, 8000, memoryRuntime, onlineQualityRecorder,
                memoryContextProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(com.yin.ai.platform.agent.pattern.reflect.StepReflector.class)
    public com.yin.ai.platform.agent.pattern.reflect.StepReflector stepReflector(
            StructuredClient structuredClient,
            com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher dispatcher) {
        return new com.yin.ai.platform.agent.pattern.reflect.LlmStepReflector(
                structuredClient, dispatcher::isAvailable);
    }

    @Bean
    @ConditionalOnMissingBean(com.yin.ai.platform.agent.pattern.research.ResearchSynthesisPolicy.class)
    public com.yin.ai.platform.agent.pattern.research.ResearchSynthesisPolicy researchSynthesisPolicy() {
        return new com.yin.ai.platform.agent.pattern.research.DefaultResearchSynthesisPolicy();
    }

    // === Pattern 实现 ===

    @Bean
    @ConditionalOnMissingBean(SimpleAgentPattern.class)
    public SimpleAgentPattern simpleAgentPattern(GatewayTextClient gatewayTextClient) {
        return new SimpleAgentPattern(gatewayTextClient);
    }

    @Bean
    @ConditionalOnMissingBean(ReactAgentPattern.class)
    @ConditionalOnBean(ReActLoop.class)
    public ReactAgentPattern reactAgentPattern(ReActLoop reActLoop) {
        return new ReactAgentPattern(reActLoop);
    }

    @Bean
    @ConditionalOnMissingBean(PlanExecuteAgentPattern.class)
    public PlanExecuteAgentPattern planExecuteAgentPattern(
            GatewayTextClient gatewayTextClient,
            StructuredClient structuredClient,
            com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher dispatcher,
            com.yin.ai.platform.agent.pattern.context.ContextManager contextManager,
            com.yin.ai.platform.agent.pattern.reflect.StepReflector reflector,
            com.yin.ai.platform.agent.pattern.executor.ParallelStepExecutor parallelStepExecutor,
            com.yin.ai.platform.workflow.load.WorkflowRegistry workflowRegistry,
            com.yin.ai.platform.agent.hitl.HitlPolicy hitlPolicy,
            com.yin.ai.platform.agent.hitl.HitlStore hitlStore,
            ClaimEvidenceQualityJudge claimEvidenceQualityJudge) {
        PlanExecuteAgentPattern pattern = new PlanExecuteAgentPattern(
                gatewayTextClient, structuredClient, dispatcher, contextManager, reflector, parallelStepExecutor);
        // [LLM 优先 / workflow 兜底] LLM 规划失败时回退到静态 workflow 计划
        pattern.setWorkflowRegistry(workflowRegistry);
        pattern.setApprovalGate(hitlPolicy, hitlStore);
        pattern.setClaimEvidenceQualityJudge(claimEvidenceQualityJudge);
        return pattern;
    }

    @Bean
    @ConditionalOnMissingBean(DeepResearchAgentPattern.class)
    public DeepResearchAgentPattern deepResearchAgentPattern(
            GatewayTextClient gatewayTextClient,
            StructuredClient structuredClient,
            com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher dispatcher,
            com.yin.ai.platform.agent.pattern.context.ContextManager contextManager,
            com.yin.ai.platform.agent.pattern.reflect.StepReflector reflector,
            com.yin.ai.platform.agent.pattern.research.ResearchSynthesisPolicy researchSynthesisPolicy,
            ClaimEvidenceQualityJudge claimEvidenceQualityJudge) {
        DeepResearchAgentPattern pattern = new DeepResearchAgentPattern(
                gatewayTextClient, structuredClient, dispatcher, contextManager, reflector, researchSynthesisPolicy);
        pattern.setClaimEvidenceQualityJudge(claimEvidenceQualityJudge);
        return pattern;
    }

    // === 统一 Runtime ===

    @Bean
    @ConditionalOnMissingBean(UnifiedAgentRuntime.class)
    public UnifiedAgentRuntime unifiedAgentRuntime(AgentRouter router,
                                                    TaskEngine taskEngine,
                                                    List<AgentPattern> patterns,
                                                    ContextStore contextStore,
                                                    EventPublisher eventPublisher,
                                                    ObjectProvider<StepArtifactStore> stepArtifactStore,
                                                    TaskTokenAccountant tokenAccountant,
                                                    ObjectProvider<AgentMemoryLifecycle> memoryLifecycle,
                                                    ObjectProvider<AgentStepPolicy> stepPolicy,
                                                    ObjectProvider<AgentPatternBridge> patternBridge,
                                                    ObjectProvider<RouterFirstMonitor> routerFirstMonitor,
                                                    ObjectProvider<AgentRuntimeMetrics> runtimeMetrics) {
        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(
                router, taskEngine, patterns, contextStore, eventPublisher,
                stepArtifactStore.getIfAvailable(), tokenAccountant, memoryLifecycle.getIfAvailable(),
                stepPolicy.getIfAvailable());
        runtime.setPatternBridge(patternBridge.getIfAvailable());
        runtime.setRouterFirstMonitor(routerFirstMonitor.getIfAvailable());
        runtime.setRuntimeMetrics(runtimeMetrics.getIfAvailable());
        return runtime;
    }

    // === Memory 整合 ===

    @Bean
    @ConditionalOnMissingBean(name = "memoryConsolidationExecutor")
    public ExecutorService memoryConsolidationExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mem-consolidation");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    @ConditionalOnMissingBean(ModeMemoryStrategyResolver.class)
    public ModeMemoryStrategyResolver modeMemoryStrategyResolver() {
        return ModeMemoryStrategyResolver.defaultResolver();
    }

    @Bean
    @ConditionalOnMissingBean(MemoryConsolidationEngine.class)
    @ConditionalOnBean(MemoryRuntime.class)
    public MemoryConsolidationEngine memoryConsolidationEngine(
            MemoryRuntime memoryRuntime, StructuredClient structuredClient) {
        return new DefaultMemoryConsolidationEngine(memoryRuntime, structuredClient);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryConsolidationSubscriber.class)
    @ConditionalOnBean({MemoryConsolidationEngine.class, AgentMemoryBridge.class})
    public MemoryConsolidationSubscriber memoryConsolidationSubscriber(
            MemoryConsolidationEngine engine, AgentMemoryBridge memoryBridge) {
        return new MemoryConsolidationSubscriber(engine, memoryBridge);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.multi-agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(AgentTeamCoordinator.class)
    public AgentTeamCoordinator agentTeamCoordinator(
            GatewayTextClient gatewayTextClient,
            FunctionRegistry functionRegistry,
            TaskEngine taskEngine,
            ContextStore contextStore,
            TaskTokenAccountant tokenAccountant,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        return new AgentTeamCoordinator(gatewayTextClient, functionRegistry, taskEngine,
                contextStore, tokenAccountant, objectMapper, meterRegistry);
    }

    @Bean
    @ConditionalOnBean(AgentTeamCoordinator.class)
    @ConditionalOnMissingBean(MultiAgentPattern.class)
    public MultiAgentPattern multiAgentPattern(AgentTeamCoordinator coordinator) {
        return new MultiAgentPattern(coordinator);
    }

    @Bean
    @ConditionalOnMissingBean(AgentMemoryLifecycle.class)
    @ConditionalOnBean({AgentMemoryBridge.class, MemoryConsolidationSubscriber.class})
    public AgentMemoryLifecycle agentMemoryLifecycle(AgentMemoryBridge bridge,
                                                      ModeMemoryStrategyResolver resolver,
                                                      MemoryConsolidationSubscriber subscriber,
                                                      @Qualifier("memoryConsolidationExecutor")
                                                      ExecutorService memoryConsolidationExecutor) {
        return new AgentMemoryLifecycle(bridge, resolver, subscriber, memoryConsolidationExecutor);
    }
}
