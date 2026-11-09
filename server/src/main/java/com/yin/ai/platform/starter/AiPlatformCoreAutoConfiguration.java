package com.yin.ai.platform.starter;

import com.yin.ai.platform.adapter.gateway.GatewayModelClientAdapter;
import com.yin.ai.platform.adapter.runtime.CoreAgentPatternBridge;
import com.yin.ai.platform.adapter.runtime.CorePatternDelegationPolicy;
import com.yin.ai.platform.adapter.runtime.LegacyFunctionToolAdapter;
import com.yin.ai.platform.adapter.runtime.LegacyHitlApprovalPolicy;
import com.yin.ai.platform.adapter.runtime.UnifiedAgentRuntimeAdapter;
import com.yin.ai.platform.adapter.spring.context.SpringExecutionContextFactory;
import com.yin.ai.platform.agent.hitl.HitlPolicy;
import com.yin.ai.platform.agent.hitl.HitlStore;
import com.yin.ai.platform.agent.pattern.AgentPatternBridge;
import com.yin.ai.platform.agent.pattern.UnifiedAgentRuntime;
import com.yin.ai.platform.core.agent.AgentRuntime;
import com.yin.ai.platform.core.agent.DefaultAgentRuntime;
import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;
import com.yin.ai.platform.core.agent.checkpoint.InMemoryAgentCheckpointStore;
import com.yin.ai.platform.core.agent.clarification.ModelResearchClarifier;
import com.yin.ai.platform.core.agent.clarification.ResearchClarifier;
import com.yin.ai.platform.core.agent.plan.ModelStepReflector;
import com.yin.ai.platform.core.agent.plan.StepReflector;
import com.yin.ai.platform.core.agent.research.DefaultResearchSynthesisPolicy;
import com.yin.ai.platform.core.agent.research.ResearchSynthesisPolicy;
import com.yin.ai.platform.core.agent.store.AgentRunStore;
import com.yin.ai.platform.core.agent.store.InMemoryAgentRunStore;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import com.yin.ai.platform.core.tool.ToolRegistry;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.quickstart.ModelClients;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Set;
import java.util.stream.Collectors;

/** Bridges container-managed legacy implementations to the framework-neutral core API. */
@AutoConfiguration
@EnableConfigurationProperties(AiPlatformProperties.class)
@ConditionalOnProperty(prefix = "ai.platform", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AiPlatformCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    public ToolRegistry aiPlatformToolRegistry() {
        return new DefaultToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(SpringExecutionContextFactory.class)
    public SpringExecutionContextFactory aiPlatformExecutionContextFactory() {
        return new SpringExecutionContextFactory();
    }

    @Bean
    @ConditionalOnMissingBean(AgentCheckpointStore.class)
    public AgentCheckpointStore aiPlatformAgentCheckpointStore() {
        return new InMemoryAgentCheckpointStore();
    }

    @Bean
    @ConditionalOnMissingBean(AgentRunStore.class)
    public AgentRunStore aiPlatformAgentRunStore() {
        return new InMemoryAgentRunStore();
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalPolicy.class)
    public ApprovalPolicy aiPlatformApprovalPolicy(
            ObjectProvider<HitlPolicy> policyProvider,
            ObjectProvider<HitlStore> storeProvider) {
        HitlPolicy policy = policyProvider.getIfAvailable();
        HitlStore store = storeProvider.getIfAvailable();
        return policy != null && store != null
                ? new LegacyHitlApprovalPolicy(policy, store) : ApprovalPolicy.NONE;
    }

    @Bean
    @ConditionalOnMissingBean(ResearchClarifier.class)
    public ResearchClarifier aiPlatformResearchClarifier() {
        return ModelResearchClarifier.standard();
    }

    @Bean
    @ConditionalOnMissingBean(StepReflector.class)
    public StepReflector aiPlatformStepReflector() {
        return ModelStepReflector.standard();
    }

    @Bean
    @ConditionalOnMissingBean(ResearchSynthesisPolicy.class)
    public ResearchSynthesisPolicy aiPlatformResearchSynthesisPolicy() {
        return DefaultResearchSynthesisPolicy.standard();
    }

    @Bean
    public SmartInitializingSingleton legacyFunctionToolRegistration(
            ToolRegistry registry,
            ObjectProvider<com.yin.ai.platform.function.Function> functions) {
        return () -> functions.orderedStream()
                .map(LegacyFunctionToolAdapter::new)
                .forEach(registry::register);
    }

    @Bean
    @ConditionalOnBean(UnifiedAgentRuntime.class)
    @ConditionalOnMissingBean(AgentRuntime.class)
    public AgentRuntime aiPlatformAgentRuntime(UnifiedAgentRuntime runtime) {
        return new UnifiedAgentRuntimeAdapter(runtime);
    }

    @Bean
    @ConditionalOnBean(UnifiedChatClient.class)
    @ConditionalOnMissingBean(ModelClient.class)
    public ModelClient aiPlatformModelClient(UnifiedChatClient gateway) {
        return new GatewayModelClientAdapter(gateway);
    }

    /**
     * 把容器里的模型客户端接到产品门面 {@code Agent.of(...)} 上。
     * <p>
     * 门面位于框架无关的 quickstart 包，不能直接依赖 Spring，因此通过
     * {@link ModelClients} 提供的注册点注入。业务开发者由此可以只写：
     * <pre>{@code
     * Result r = Agent.of("内容审核").prompt("...").build().run(article);
     * }</pre>
     * 而不需要自己实现 ModelClient。
     */
    @Bean
    @ConditionalOnBean(ModelClient.class)
    public SmartInitializingSingleton quickstartModelClientRegistrar(ModelClient modelClient) {
        return () -> ModelClients.register(modelClient);
    }

    @Bean
    @ConditionalOnBean(ModelClient.class)
    @ConditionalOnMissingBean(AgentPatternBridge.class)
    public AgentPatternBridge coreAgentPatternBridge(ModelClient modelClient,
                                                     ToolRegistry toolRegistry,
                                                     ApprovalPolicy approvalPolicy,
                                                     ResearchClarifier researchClarifier,
                                                     StepReflector stepReflector,
                                                     ResearchSynthesisPolicy synthesisPolicy,
                                                     AiPlatformProperties properties) {
        AiPlatformProperties.CoreDelegation configured = properties.getCoreDelegation();
        Set<com.yin.ai.platform.core.agent.pattern.AgentMode> modes = configured.getModes().stream()
                .map(com.yin.ai.platform.core.agent.pattern.AgentMode::parse)
                .collect(Collectors.toUnmodifiableSet());
        CorePatternDelegationPolicy policy = new CorePatternDelegationPolicy(
                configured.isEnabled(), modes, configured.getRequestOverrideKey(),
                configured.getMaxReactIterations(), configured.getMaxPlanRounds(),
                configured.getMaxStepRetries());
        return new CoreAgentPatternBridge(modelClient, toolRegistry, policy,
                approvalPolicy, researchClarifier, stepReflector, synthesisPolicy);
    }

    @Bean
    @ConditionalOnBean(ModelClient.class)
    @ConditionalOnMissingBean(value = {AgentRuntime.class, UnifiedAgentRuntime.class})
    public AgentRuntime defaultAiPlatformAgentRuntime(ModelClient modelClient,
                                                       ToolRegistry toolRegistry,
                                                       AgentCheckpointStore checkpointStore,
                                                       AgentRunStore runStore,
                                                       ApprovalPolicy approvalPolicy,
                                                       ResearchClarifier researchClarifier,
                                                       StepReflector stepReflector,
                                                       ResearchSynthesisPolicy synthesisPolicy) {
        return DefaultAgentRuntime.builder()
                .model(modelClient)
                .tools(toolRegistry)
                .checkpoints(checkpointStore)
                .runStore(runStore)
                .approvals(approvalPolicy)
                .researchClarifier(researchClarifier)
                .stepReflector(stepReflector)
                .researchSynthesisPolicy(synthesisPolicy)
                .build();
    }
}
