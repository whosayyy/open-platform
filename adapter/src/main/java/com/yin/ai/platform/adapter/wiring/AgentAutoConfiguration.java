package com.yin.ai.platform.adapter.wiring;

import com.yin.ai.platform.agent.ReActLoop;
import com.yin.ai.platform.agent.executor.AgentExecutor;
import com.yin.ai.platform.agent.executor.DefaultAgentExecutor;
import com.yin.ai.platform.agent.hitl.HitlPolicy;
import com.yin.ai.platform.agent.hitl.HitlStore;
import com.yin.ai.platform.agent.hitl.InMemoryHitlStore;
import com.yin.ai.platform.adapter.runtime.persistence.RedisHitlStore;
import com.yin.ai.platform.agent.hitl.ToolExecutionHitlPolicy;
import com.yin.ai.platform.agent.integration.AgentSkillFunctionRegistrar;
import com.yin.ai.platform.agent.memory.AgentMemoryBridge;
import com.yin.ai.platform.agent.rag.AgentRagBridge;
import com.yin.ai.platform.agent.memory.AgentMemoryConfig;
import com.yin.ai.platform.agent.observer.DefaultObserver;
import com.yin.ai.platform.agent.observer.Observer;
import com.yin.ai.platform.agent.planner.LLMPlanner;
import com.yin.ai.platform.agent.planner.Planner;
import com.yin.ai.platform.agent.reflector.LLMReflector;
import com.yin.ai.platform.agent.reflector.Reflector;
import com.yin.ai.platform.agent.trace.StepTraceRecorder;
import com.yin.ai.platform.agent.tool.ToolSelectionPolicy;
import org.springframework.beans.factory.ObjectProvider;
import com.yin.ai.platform.agent.runtime.AgentRuntime;
import com.yin.ai.platform.agent.runtime.DefaultAgentRuntime;
import com.yin.ai.platform.agent.strategy.ComplexityAnalyzer;
import com.yin.ai.platform.agent.strategy.ComplexityConfig;
import com.yin.ai.platform.agent.strategy.DefaultComplexityAnalyzer;
import com.yin.ai.platform.agent.strategy.StrategyRouter;
import com.yin.ai.platform.engine.ExecutionEngine;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.memory.orchestration.MemoryOrchestrator;
import com.yin.ai.platform.memory.config.MemoryProperties;
import com.yin.ai.platform.memory.strategy.DefaultMemoryStrategySelector;
import com.yin.ai.platform.observe.OnlineQualityRecorder;
import com.yin.ai.platform.rag.grounding.RagGroundingGuard;
import com.yin.ai.platform.memory.strategy.MemoryStrategyConfig;
import com.yin.ai.platform.memory.strategy.MemoryStrategySelector;
import com.yin.ai.platform.rag.trigger.KnowledgeBoundaryDetector;
import com.yin.ai.platform.rag.trigger.RAGTriggerPolicy;
import com.yin.ai.platform.rag.trigger.RAGTriggerDecisionEngine;
import com.yin.ai.platform.rag.trigger.RuleBasedBoundaryDetector;
import com.yin.ai.platform.runtime.SkillRuntime;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent Runtime 自动配置。
 * <p>
 * 所有 SPI 默认使用 NoOp 占位实现。D2-D4 添加真实实现时，
 * 使用 {@code @ConditionalOnMissingBean} 确保自定义 Bean 自动覆盖默认值。
 * <p>
 * D2: Planner 默认使用 TemplatePlanner（单步通用模板），LLMPlanner 由业务方按需创建。
 * D4: Executor 默认使用 DefaultAgentExecutor（FunctionRegistry + UnifiedChatClient），
 *      Observer 默认使用 DefaultObserver（启发式），
 *      Reflector 默认使用 LLMReflector（LLM 驱动 + 启发式降级）。
 */
@Configuration
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Planner agentPlanner(UnifiedChatClient chatClient, FunctionRegistry functionRegistry,
                                ObjectProvider<ToolSelectionPolicy> toolSelectionPolicy) {
        return new LLMPlanner(chatClient, functionRegistry, null, toolSelectionPolicy.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentExecutor agentExecutor(FunctionRegistry functionRegistry,
                                        UnifiedChatClient unifiedChatClient,
                                        ObjectProvider<StepTraceRecorder> stepTraceProvider,
                                        ObjectProvider<RagGroundingGuard> groundingGuardProvider) {
        // [全链路追踪] 注入 StepTraceRecorder（service 层 JDBC 实现，未装配时 NOOP 降级）
        return new DefaultAgentExecutor(functionRegistry, unifiedChatClient,
                stepTraceProvider.getIfAvailable(), groundingGuardProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public Observer agentObserver() {
        return new DefaultObserver();
    }

    @Bean
    @ConditionalOnMissingBean
    public Reflector agentReflector(UnifiedChatClient unifiedChatClient) {
        return new LLMReflector(unifiedChatClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReActLoop reActLoop(Planner planner, AgentExecutor executor,
                                Observer observer, Reflector reflector,
                                AgentMemoryBridge memoryBridge,
                                AgentRagBridge ragBridge,
                                RAGTriggerDecisionEngine ragTrigger,
                                ObjectProvider<OnlineQualityRecorder> onlineQualityRecorder) {
        ReActLoop loop = new ReActLoop(planner, executor, observer, reflector);
        loop.setMemoryBridge(memoryBridge);
        loop.setRagBridge(ragBridge);
        loop.setRagTrigger(ragTrigger);
        loop.setOnlineQualityRecorder(onlineQualityRecorder.getIfAvailable());
        return loop;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentMemoryBridge agentMemoryBridge(MemoryRuntime memoryRuntime,
                                                MemoryStrategySelector strategySelector,
                                                ObjectProvider<MemoryOrchestrator> orchestratorProvider,
                                                MemoryProperties memoryProperties) {
        AgentMemoryBridge bridge = new AgentMemoryBridge(memoryRuntime, AgentMemoryConfig.defaults(),
                strategySelector, orchestratorProvider.getIfAvailable());
        bridge.setOrchestratedRetrieval(memoryProperties.getOrchestration().isRetrievalEnabled());
        return bridge;
    }

    // ——— Phase 2: Dynamic Memory Strategy ———

    @Bean
    @ConditionalOnMissingBean
    public MemoryStrategySelector defaultMemoryStrategySelector() {
        return new DefaultMemoryStrategySelector();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryStrategyConfig memoryStrategyConfig() {
        return MemoryStrategyConfig.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentSkillFunctionRegistrar agentSkillFunctionRegistrar(
            SkillLoader skillLoader, SkillRuntime skillRuntime,
            FunctionRegistry functionRegistry) {
        return new AgentSkillFunctionRegistrar(skillLoader, skillRuntime, functionRegistry);
    }

    // ——— Phase 1: Complexity Analysis & Strategy Routing ———

    @Bean
    @ConditionalOnMissingBean
    public ComplexityAnalyzer defaultComplexityAnalyzer() {
        return new DefaultComplexityAnalyzer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ComplexityConfig complexityConfig() {
        return ComplexityConfig.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public StrategyRouter strategyRouter(List<ComplexityAnalyzer> analyzers,
                                          ComplexityConfig config) {
        return new StrategyRouter(analyzers, config);
    }

    // ——— Phase 4: Intelligent RAG Triggering ———

    @Bean
    @ConditionalOnMissingBean
    public AgentRagBridge agentRagBridge() {
        return new AgentRagBridge() {
            @Override
            public String retrieve(String query, Long userId) {
                return "";
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBoundaryDetector knowledgeBoundaryDetector() {
        return new RuleBasedBoundaryDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    public RAGTriggerPolicy ragTriggerPolicy() {
        return RAGTriggerPolicy.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public RAGTriggerDecisionEngine ragTriggerDecisionEngine(
            KnowledgeBoundaryDetector detector, RAGTriggerPolicy policy) {
        return new RAGTriggerDecisionEngine(detector, policy);
    }

    // ——— HITL: Human-in-the-Loop ———

    @Bean
    @ConditionalOnMissingBean
    public HitlPolicy hitlPolicy() {
        return new ToolExecutionHitlPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public HitlStore hitlStore(ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson != null) {
            return new RedisHitlStore(redisson);
        }
        return new InMemoryHitlStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntime agentRuntime(ExecutionEngine executionEngine, Planner planner,
                                      AgentExecutor executor, Observer observer,
                                      Reflector reflector, AgentMemoryBridge memoryBridge,
                                      AgentRagBridge ragBridge,
                                      List<ComplexityAnalyzer> analyzers,
                                      HitlPolicy hitlPolicy, HitlStore hitlStore,
                                      ObjectProvider<OnlineQualityRecorder> onlineQualityRecorder,
                                      ObjectProvider<RAGTriggerDecisionEngine> ragTriggerProvider) {
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(executionEngine, planner, executor, observer, reflector,
                memoryBridge, ragBridge, analyzers);
        runtime.setHitlPolicy(hitlPolicy);
        runtime.setHitlStore(hitlStore);
        runtime.setOnlineQualityRecorder(onlineQualityRecorder.getIfAvailable());
        // [企业级缺口修复] 注入 RAG 触发决策引擎——DefaultAgentRuntime 内部 ReActLoop 的 shouldTrigger 生效
        runtime.setRagTrigger(ragTriggerProvider.getIfAvailable());
        return runtime;
    }
}
