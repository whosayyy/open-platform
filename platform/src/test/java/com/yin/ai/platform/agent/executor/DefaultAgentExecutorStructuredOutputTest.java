package com.yin.ai.platform.agent.executor;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.StructuredOutputContract;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayMessage;
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
 * 验证 {@link DefaultAgentExecutor} 的结构化输出契约钩子：
 * 契约存在时注册强制工具、优先取 toolCallArguments 作为最终结果、注入【输出契约】消息；
 * 无契约时行为不变。
 */
@DisplayName("DefaultAgentExecutor — 结构化输出契约")
class DefaultAgentExecutorStructuredOutputTest {

    private static final String TOOL_NAME = "submitResult";

    private ExecutionContext execCtx;
    private AgentContext ctx;
    private StubChatClient chatClient;
    private DefaultAgentExecutor executor;

    @BeforeEach
    void setUp() {
        execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
        chatClient = new StubChatClient();
        executor = new DefaultAgentExecutor(new FunctionRegistry(), chatClient);
    }

    private void contextWith(AgentConfig config) {
        AgentTask task = new AgentTask("生成结构化结果", Map.of(), null, config);
        ctx = new AgentContext(execCtx, task, 0);
    }

    private static final AgentConfig CONTRACT_CONFIG = AgentConfig.of(10,
            Duration.ofMinutes(1), new StructuredOutputContract(TOOL_NAME, Map.of("type", "object")));

    @Test
    @DisplayName("契约 + 工具参数 → StepResult 返回工具参数，options 注册强制工具")
    void contractWithToolArgsReturnsArgs() {
        contextWith(CONTRACT_CONFIG);
        String argsJson = "{\"result\":\"结构化结果内容\"}";
        chatClient.toolCallArguments = argsJson;
        Step step = Step.of("s1", "生成最终结果");

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo(argsJson);
        assertThat(chatClient.lastRequest.getOptions().getForcedToolName()).isEqualTo(TOOL_NAME);
        assertThat(chatClient.lastRequest.getOptions().getOutputSchema()).containsKey("type");
        assertThat(chatClient.lastRequest.getOptions().isJsonResponse()).isFalse();
        assertThat(chatClient.lastRequest.getOptions().isEnableFunctionCalling()).isFalse();
    }

    @Test
    @DisplayName("契约存在但无工具参数 → 回退 content")
    void contractWithoutToolArgsFallsBackToContent() {
        contextWith(CONTRACT_CONFIG);
        chatClient.content = "纯文本响应";
        Step step = Step.of("s1", "生成最终结果");

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("纯文本响应");
    }

    @Test
    @DisplayName("无契约 → 行为不变，jsonResponse 走旧配置")
    void noContractKeepsLegacyBehavior() {
        contextWith(AgentConfig.of(10, Duration.ofMinutes(1), true));
        chatClient.content = "{\"items\":[1,2]}";
        Step step = Step.of("s1", "生成结果");

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isTrue();
        assertThat(chatClient.lastRequest.getOptions().getForcedToolName()).isNull();
        assertThat(chatClient.lastRequest.getOptions().isJsonResponse()).isTrue();
    }

    @Test
    @DisplayName("契约消息注入到 buildMessages")
    void contractMessageInjected() {
        contextWith(CONTRACT_CONFIG);
        chatClient.toolCallArguments = "{}";
        Step step = Step.of("s1", "生成结果");

        executor.execute(step, ctx);

        boolean hasContract = chatClient.lastRequest.getMessages().stream()
                .filter(m -> m.getRole() == GatewayMessage.Role.system)
                .anyMatch(m -> m.getContent().contains("输出契约") && m.getContent().contains(TOOL_NAME));
        assertThat(hasContract).isTrue();
    }

    @Test
    @DisplayName("systemPrompt 与 RAG 上下文照常注入，契约共存")
    void contractCoexistsWithSystemPromptAndRag() {
        contextWith(CONTRACT_CONFIG);
        ctx.putVariable("systemPrompt", "你是结构化数据服务");
        ctx.putVariable("agent.ragContext", "参考资料A");
        chatClient.toolCallArguments = "{}";
        Step step = Step.of("s1", "生成结果");

        executor.execute(step, ctx);

        String allContent = chatClient.lastRequest.getMessages().stream()
                .map(GatewayMessage::getContent).reduce("", String::concat);
        assertThat(allContent).contains("你是结构化数据服务");
        assertThat(allContent).contains("参考资料A");
        assertThat(allContent).contains("输出契约");
    }

    static class StubChatClient implements UnifiedChatClient {
        String content;
        String toolCallArguments;
        GatewayRequest lastRequest;

        @Override
        public GatewayResponse chat(GatewayRequest request) {
            this.lastRequest = request;
            GatewayResponse resp = new GatewayResponse(content);
            resp.setToolCallArguments(toolCallArguments);
            return resp;
        }

        @Override
        public Flux<StreamPacket> chatStream(GatewayRequest request) {
            throw new UnsupportedOperationException("stream not used in executor");
        }
    }
}
