package com.yin.ai.platform.starter;

import com.yin.ai.platform.adapter.runtime.UnifiedAgentRuntimeAdapter;
import com.yin.ai.platform.adapter.runtime.LegacyHitlApprovalPolicy;
import com.yin.ai.platform.adapter.runtime.persistence.RedisAgentCheckpointStore;
import com.yin.ai.platform.adapter.runtime.persistence.RedisAgentRunStore;
import com.yin.ai.platform.agent.hitl.HitlPolicy;
import com.yin.ai.platform.agent.hitl.HitlStore;
import com.yin.ai.platform.agent.hitl.InMemoryHitlStore;
import com.yin.ai.platform.agent.hitl.ToolExecutionHitlPolicy;
import com.yin.ai.platform.agent.pattern.UnifiedAgentRuntime;
import com.yin.ai.platform.agent.pattern.AgentPatternBridge;
import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentRuntime;
import com.yin.ai.platform.core.agent.AgentStatus;
import com.yin.ai.platform.core.agent.DefaultAgentRuntime;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;
import com.yin.ai.platform.core.agent.checkpoint.InMemoryAgentCheckpointStore;
import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.clarification.ResearchClarifier;
import com.yin.ai.platform.core.agent.plan.StepReflector;
import com.yin.ai.platform.core.agent.research.ResearchSynthesisPolicy;
import com.yin.ai.platform.core.agent.store.AgentRunStore;
import com.yin.ai.platform.core.agent.store.InMemoryAgentRunStore;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.tool.ToolRegistry;
import com.yin.ai.platform.function.Function;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.function.FunctionInput;
import com.yin.ai.platform.function.FunctionResult;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.StreamPacket;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("deprecation")
class AiPlatformCoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiPlatformCoreAutoConfiguration.class));

    @Test
    void createsCoreBeansWithoutAUnifiedRuntime() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ToolRegistry.class);
            assertThat(context).hasSingleBean(AgentCheckpointStore.class);
            assertThat(context).hasSingleBean(AgentRunStore.class);
            assertThat(context).hasSingleBean(ApprovalPolicy.class);
            assertThat(context).hasSingleBean(ResearchClarifier.class);
            assertThat(context).hasSingleBean(StepReflector.class);
            assertThat(context).hasSingleBean(ResearchSynthesisPolicy.class);
            assertThat(context).hasSingleBean(AiPlatformProperties.class);
            assertThat(context).doesNotHaveBean(com.yin.ai.platform.core.agent.AgentRuntime.class);
        });
    }

    @Test
    void adaptsLegacyFunctionsIntoTheCoreRegistry() {
        runner.withUserConfiguration(LegacyFunctionConfiguration.class).run(context -> {
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertThat(registry.find("legacyEcho")).isPresent();
            assertThat(registry.definitions()).extracting(definition -> definition.name())
                    .containsExactly("legacyEcho");
        });
    }

    @Test
    void adaptsLegacyHitlBeansIntoTheCoreApprovalPort() {
        runner.withUserConfiguration(LegacyHitlConfiguration.class).run(context ->
                assertThat(context.getBean(ApprovalPolicy.class))
                        .isInstanceOf(LegacyHitlApprovalPolicy.class));
    }

    @Test
    void canDisableAllPlatformCoreAutoConfiguration() {
        runner.withPropertyValues("ai.platform.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ToolRegistry.class);
            assertThat(context).doesNotHaveBean(AiPlatformProperties.class);
        });
    }

    @Test
    void exposesTheUnifiedGatewayThroughTheCoreModelSpi() {
        runner.withUserConfiguration(GatewayConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(ModelClient.class);
            assertThat(context).hasSingleBean(AgentPatternBridge.class);
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context.getBean(AgentRuntime.class)).isInstanceOf(DefaultAgentRuntime.class);

            var result = context.getBean(AgentRuntime.class)
                    .execute(AgentRequest.of("hello", null, null, null));
            assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
            assertThat(result.output()).isEqualTo("ok");
        });
    }

    @Test
    void prefersTheLegacyRuntimeAdapterWhenAUnifiedRuntimeExists() {
        runner.withUserConfiguration(GatewayConfiguration.class, LegacyRuntimeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context.getBean(AgentRuntime.class))
                            .isInstanceOf(UnifiedAgentRuntimeAdapter.class);
                });
    }

    @Test
    void bindsCorePatternDelegationRolloutProperties() {
        runner.withUserConfiguration(GatewayConfiguration.class)
                .withPropertyValues(
                        "ai.platform.core-delegation.enabled=true",
                        "ai.platform.core-delegation.modes=react,plan-execute",
                        "ai.platform.core-delegation.max-react-iterations=5")
                .run(context -> {
                    AiPlatformProperties.CoreDelegation delegation = context
                            .getBean(AiPlatformProperties.class).getCoreDelegation();
                    assertThat(delegation.isEnabled()).isTrue();
                    assertThat(delegation.getModes()).containsExactlyInAnyOrder(
                            "react", "plan-execute");
                    assertThat(delegation.getMaxReactIterations()).isEqualTo(5);
                    assertThat(context).hasSingleBean(AgentPatternBridge.class);
                });
    }

    @Test
    void explicitlyOptsCoreAgentStateIntoRedisAdapters() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiPlatformAgentRedisAutoConfiguration.class,
                        AiPlatformCoreAutoConfiguration.class))
                .withUserConfiguration(RedisConfiguration.class)
                .withPropertyValues(
                        "ai.platform.agent-state-store.type=redis",
                        "ai.platform.agent-state-store.redis-key-prefix=test:agent")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRunStore.class);
                    assertThat(context.getBean(AgentRunStore.class))
                            .isInstanceOf(RedisAgentRunStore.class);
                    assertThat(context).hasSingleBean(AgentCheckpointStore.class);
                    assertThat(context.getBean(AgentCheckpointStore.class))
                            .isInstanceOf(RedisAgentCheckpointStore.class);
                    assertThat(context.getBean(AiPlatformProperties.class)
                            .getAgentStateStore().getRedisKeyPrefix())
                            .isEqualTo("test:agent");
                });
    }

    @Test
    void keepsInMemoryAgentStateAsTheDefaultWhenRedisIsAvailable() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiPlatformAgentRedisAutoConfiguration.class,
                        AiPlatformCoreAutoConfiguration.class))
                .withUserConfiguration(RedisConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean(AgentRunStore.class))
                            .isInstanceOf(InMemoryAgentRunStore.class);
                    assertThat(context.getBean(AgentCheckpointStore.class))
                            .isInstanceOf(InMemoryAgentCheckpointStore.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyFunctionConfiguration {
        @Bean
        Function legacyEcho() {
            return new Function() {
                @Override
                public FunctionDefinition getDefinition() {
                    return FunctionDefinition.builder().name("legacyEcho").description("echo")
                            .parameters(List.of()).risk(FunctionDefinition.Risk.READ_ONLY).build();
                }

                @Override
                public FunctionResult execute(FunctionInput input) {
                    return FunctionResult.ok(input.getParameters());
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean
        UnifiedChatClient unifiedChatClient() {
            return new UnifiedChatClient() {
                @Override
                public GatewayResponse chat(GatewayRequest request) {
                    return new GatewayResponse("ok");
                }

                @Override
                public Flux<StreamPacket> chatStream(GatewayRequest request) {
                    return Flux.empty();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyHitlConfiguration {
        @Bean
        HitlPolicy hitlPolicy() {
            return new ToolExecutionHitlPolicy();
        }

        @Bean
        HitlStore hitlStore() {
            return new InMemoryHitlStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyRuntimeConfiguration {
        @Bean
        UnifiedAgentRuntime unifiedAgentRuntime() {
            return org.mockito.Mockito.mock(UnifiedAgentRuntime.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisConfiguration {
        @Bean
        RedissonClient redissonClient() {
            return org.mockito.Mockito.mock(RedissonClient.class);
        }
    }
}
