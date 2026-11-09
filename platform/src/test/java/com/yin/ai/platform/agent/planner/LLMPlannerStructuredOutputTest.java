package com.yin.ai.platform.agent.planner;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.StructuredOutputContract;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link LLMPlanner} 的结构化输出契约钩子：
 * 契约存在时注册强制工具并优先取 toolCallArguments → Plan.direct；
 * 无契约时行为不变。
 */
@DisplayName("LLMPlanner — 结构化输出契约")
class LLMPlannerStructuredOutputTest {

    private static final String TOOL_NAME = "submitResult";

    private StubChatClient chatClient;
    private ExecutionContext execCtx;

    @BeforeEach
    void setUp() {
        chatClient = new StubChatClient();
        execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
    }

    private AgentContext contextWith(AgentConfig config) {
        AgentTask task = new AgentTask("分析AI芯片市场", Map.of(), null, config);
        return new AgentContext(execCtx, task, 0);
    }

    private static final AgentConfig CONTRACT_CONFIG = AgentConfig.of(10,
            Duration.ofMinutes(1), new StructuredOutputContract(TOOL_NAME, Map.of("type", "object")));

    @Test
    @DisplayName("契约 + 工具参数 → Plan.direct，options 注册强制工具")
    void contractWithToolArgsReturnsDirect() {
        String argsJson = "{\"result\":\"AI芯片市场正扩张\"}";
        chatClient.response = new GatewayResponse();
        chatClient.response.setToolCallArguments(argsJson);

        LLMPlanner planner = new LLMPlanner(chatClient);
        Plan plan = planner.plan(contextWith(CONTRACT_CONFIG));

        assertThat(plan.isDirectResult()).isTrue();
        assertThat(plan.result()).isEqualTo(argsJson);

        assertThat(chatClient.lastRequest.getOptions().getForcedToolName()).isEqualTo(TOOL_NAME);
        assertThat(chatClient.lastRequest.getOptions().getOutputSchema()).containsKey("type");
        assertThat(chatClient.lastRequest.getOptions().isJsonResponse()).isFalse();
        assertThat(chatClient.lastRequest.getOptions().isEnableFunctionCalling()).isFalse();
    }

    @Test
    @DisplayName("契约存在但无工具调用 → 回退 content 解析步骤")
    void contractWithoutToolArgsParsesSteps() {
        chatClient.response = new GatewayResponse("""
                {"steps": [
                  {"id": "s1", "description": "搜索资料", "tool": null,
                   "toolParams": {}, "dependsOn": []}
                ]}""");

        LLMPlanner planner = new LLMPlanner(chatClient);
        Plan plan = planner.plan(contextWith(CONTRACT_CONFIG));

        assertThat(plan.hasSteps()).isTrue();
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).id()).isEqualTo("s1");
    }

    @Test
    @DisplayName("无契约 → 行为不变，不设置强制工具")
    void noContractKeepsLegacyBehavior() {
        chatClient.response = new GatewayResponse("""
                {"steps": [
                  {"id": "s1", "description": "分析", "tool": null,
                   "toolParams": {}, "dependsOn": []}
                ]}""");

        AgentConfig plainConfig = AgentConfig.of(10, Duration.ofMinutes(1), true);
        LLMPlanner planner = new LLMPlanner(chatClient);
        Plan plan = planner.plan(contextWith(plainConfig));

        assertThat(plan.hasSteps()).isTrue();
        assertThat(chatClient.lastRequest.getOptions().getForcedToolName()).isNull();
        assertThat(chatClient.lastRequest.getOptions().isJsonResponse()).isTrue();
    }

    @Test
    @DisplayName("契约指令包含在消息中")
    void contractInstructionIncludedInMessages() {
        chatClient.response = new GatewayResponse();
        chatClient.response.setToolCallArguments("{}");

        LLMPlanner planner = new LLMPlanner(chatClient);
        planner.plan(contextWith(CONTRACT_CONFIG));

        String lastMessage = chatClient.lastRequest.getMessages()
                .get(chatClient.lastRequest.getMessages().size() - 1).getContent();
        assertThat(lastMessage).contains("输出契约");
        assertThat(lastMessage).contains(TOOL_NAME);
    }

    static class StubChatClient implements UnifiedChatClient {
        GatewayResponse response;
        GatewayRequest lastRequest;

        @Override
        public GatewayResponse chat(GatewayRequest request) {
            this.lastRequest = request;
            return response;
        }

        @Override
        public Flux<StreamPacket> chatStream(GatewayRequest request) {
            throw new UnsupportedOperationException("stream not used in planner");
        }
    }
}
