package com.yin.ai.platform.agent.executor;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.function.FunctionResult;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.StreamPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultAgentExecutor")
class DefaultAgentExecutorTest {

    private ExecutionContext execCtx;
    private AgentContext ctx;
    private StubFunctionRegistry fnRegistry;
    private StubChatClient chatClient;
    private DefaultAgentExecutor executor;

    @BeforeEach
    void setUp() {
        execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
        AgentConfig config = AgentConfig.defaults();
        AgentTask task = new AgentTask("test goal", Map.of(), null, config);
        ctx = new AgentContext(execCtx, task, 0);
        fnRegistry = new StubFunctionRegistry();
        chatClient = new StubChatClient("LLM response");
        executor = new DefaultAgentExecutor(fnRegistry, chatClient);
    }

    @Test
    @DisplayName("tool step invokes FunctionRegistry and returns success")
    void toolStepSuccess() {
        Step step = Step.tool("s1", "search AI chip", "searchNews", Map.of("query", "AI"));

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("result from searchNews");
        assertThat(fnRegistry.lastInvoked).isEqualTo("searchNews");
    }

    @Test
    @DisplayName("tool step with unknown tool returns failure")
    void toolStepUnknownTool() {
        Step step = Step.tool("s1", "bad step", "unknownTool", Map.of());

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown function");
    }

    @Test
    @DisplayName("tool step blocked by toolFilter whitelist")
    void toolStepBlockedByWhitelist() {
        ctx.putVariable("toolFilter", Set.of("allowedTool"));
        Step step = Step.tool("s1", "restricted step", "blockedTool", Map.of());

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("whitelist");
        assertThat(result.error()).contains("blockedTool");
        assertThat(fnRegistry.lastInvoked).isNull();
    }

    @Test
    @DisplayName("tool step allowed when in whitelist")
    void toolStepAllowedByWhitelist() {
        ctx.putVariable("toolFilter", Set.of("searchNews", "getDetail"));
        Step step = Step.tool("s1", "allowed step", "searchNews", Map.of());

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isTrue();
        assertThat(fnRegistry.lastInvoked).isEqualTo("searchNews");
    }

    @Test
    @DisplayName("LLM step calls UnifiedChatClient and returns content")
    void llmStepSuccess() {
        Step step = Step.of("s1", "analyze data");

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("LLM response");
        assertThat(chatClient.lastRequest.getCaller()).isEqualTo("agent.executor");
    }

    @Test
    @DisplayName("LLM step null content returns empty string")
    void llmStepNullContent() {
        chatClient.setContent(null);
        Step step = Step.of("s1", "analyze");

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEmpty();
    }

    @Test
    @DisplayName("LLM step accumulates token usage in context")
    void llmStepAccumulatesTokens() {
        chatClient.setTokenUsage(new TokenUsage(100, 50, 150));
        Step step = Step.of("s1", "analyze");

        executor.execute(step, ctx);

        assertThat(ctx.getVariable("agent.totalTokens", Integer.class)).isEqualTo(150);
    }

    @Test
    @DisplayName("exception in executor returns StepResult.fail")
    void exceptionReturnsFail() {
        fnRegistry.setThrowOnInvoke(true);
        Step step = Step.tool("s1", "trigger error", "badTool", Map.of());

        StepResult result = executor.execute(step, ctx);

        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("messages are built from context history for LLM steps")
    void messagesFromContextHistory() {
        ctx.addMessage("user", "What is AI?");
        Step step = Step.of("s2", "answer question");

        executor.execute(step, ctx);

        assertThat(chatClient.lastRequest.getMessages()).hasSize(2);
        assertThat(chatClient.lastRequest.getMessages().get(0).getContent()).isEqualTo("What is AI?");
        assertThat(chatClient.lastRequest.getMessages().get(1).getContent()).isEqualTo("answer question");
    }

    // ——— Stubs ———

    static class StubFunctionRegistry extends FunctionRegistry {
        String lastInvoked;
        FunctionResult stubResult = FunctionResult.ok("result from searchNews");
        boolean throwOnInvoke;

        void setThrowOnInvoke(boolean v) { this.throwOnInvoke = v; }

        @Override
        public FunctionResult invoke(String name, java.util.Map<String, Object> params) {
            if (throwOnInvoke) {
                throw new RuntimeException("forced error");
            }
            lastInvoked = name;
            if ("unknownTool".equals(name)) {
                throw new IllegalArgumentException("Unknown function: " + name);
            }
            stubResult.setData("result from " + name);
            return stubResult;
        }
    }

    static class StubChatClient implements UnifiedChatClient {
        String content;
        TokenUsage tokenUsage;
        GatewayRequest lastRequest;

        StubChatClient(String content) {
            this.content = content;
        }

        void setContent(String c) { this.content = c; }
        void setTokenUsage(TokenUsage t) { this.tokenUsage = t; }

        @Override
        public GatewayResponse chat(GatewayRequest request) {
            this.lastRequest = request;
            GatewayResponse resp = new GatewayResponse(content);
            resp.setTokenUsage(tokenUsage);
            return resp;
        }

        @Override
        public Flux<StreamPacket> chatStream(GatewayRequest request) {
            throw new UnsupportedOperationException("stream not used in executor");
        }
    }
}
