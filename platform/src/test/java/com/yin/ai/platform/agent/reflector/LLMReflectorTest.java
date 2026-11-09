package com.yin.ai.platform.agent.reflector;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.observer.Observation;
import com.yin.ai.platform.agent.observer.Observation.ObservationStatus;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LLMReflector")
class LLMReflectorTest {

    private AgentContext ctx;
    private StubChatClient chatClient;
    private LLMReflector reflector;
    private Observation successObs;
    private Observation failureObs;

    @BeforeEach
    void setUp() {
        ExecutionContext execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
        AgentConfig config = AgentConfig.defaults();
        AgentTask task = new AgentTask("analyze AI chip market", Map.of(), null, config);
        ctx = new AgentContext(execCtx, task, 0);
        chatClient = new StubChatClient();
        reflector = new LLMReflector(chatClient);
        successObs = Observation.success("found relevant data");
        failureObs = Observation.failure("connection error");
    }

    @Test
    @DisplayName("LLM returns COMPLETE → Decision.COMPLETE")
    void llmReturnsComplete() {
        chatClient.setContent("COMPLETE");

        Decision decision = reflector.reflect(successObs, ctx);

        assertThat(decision).isEqualTo(Decision.COMPLETE);
    }

    @Test
    @DisplayName("LLM returns RETRY → Decision.RETRY")
    void llmReturnsRetry() {
        chatClient.setContent("RETRY");

        Decision decision = reflector.reflect(successObs, ctx);

        assertThat(decision).isEqualTo(Decision.RETRY);
    }

    @Test
    @DisplayName("LLM returns unknown text → Decision.CONTINUE")
    void llmReturnsUnknown() {
        chatClient.setContent("SOMETHING_ELSE");

        Decision decision = reflector.reflect(successObs, ctx);

        assertThat(decision).isEqualTo(Decision.CONTINUE);
    }

    @Test
    @DisplayName("LLM returns null → Decision.CONTINUE")
    void llmReturnsNull() {
        chatClient.setContent(null);

        Decision decision = reflector.reflect(successObs, ctx);

        assertThat(decision).isEqualTo(Decision.CONTINUE);
    }

    @Test
    @DisplayName("LLM fails → heuristic fallback to CONTINUE")
    void llmFailsFallback() {
        chatClient.setThrowOnCall(true);
        ctx.incrementStep();

        Decision decision = reflector.reflect(successObs, ctx);

        assertThat(decision).isEqualTo(Decision.CONTINUE);
    }

    @Test
    @DisplayName("FAILURE + retries exceeded → COMPLETE (heuristic)")
    void failureWithRetriesExceeded() {
        AgentConfig tightConfig = new AgentConfig(15, Duration.ofMinutes(10), 1, false, 3,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentTask tightTask = new AgentTask("test", Map.of(), null, tightConfig);
        AgentContext tightCtx = new AgentContext(
                new ExecutionContext(UUID.randomUUID().toString(), null, null, null, null,
                        Map.of(), null, null, Instant.now(), Map.of()),
                tightTask, 0);
        tightCtx.incrementRetryCount(); // retries = 1, maxRetries = 1, so exceeded

        Decision decision = reflector.reflect(failureObs, tightCtx);

        assertThat(decision).isEqualTo(Decision.COMPLETE);
    }

    @Test
    @DisplayName("SUCCESS + maxSteps reached → COMPLETE (heuristic)")
    void successWithMaxStepsReached() {
        AgentConfig tightConfig = new AgentConfig(5, Duration.ofMinutes(10), 3, false, 3,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentTask tightTask = new AgentTask("test", Map.of(), null, tightConfig);
        AgentContext tightCtx = new AgentContext(
                new ExecutionContext(UUID.randomUUID().toString(), null, null, null, null,
                        Map.of(), null, null, Instant.now(), Map.of()),
                tightTask, 0);
        for (int i = 0; i < 5; i++) {
            tightCtx.incrementStep();
        }

        Decision decision = reflector.reflect(successObs, tightCtx);

        assertThat(decision).isEqualTo(Decision.COMPLETE);
    }

    @Test
    @DisplayName("custom ReflectorPromptStrategy is used")
    void customPromptStrategy() {
        chatClient.setContent("COMPLETE");
        ReflectorPromptStrategy custom = (obs, c) -> "CUSTOM PROMPT: " + obs.summary();

        LLMReflector customReflector = new LLMReflector(chatClient, custom);
        customReflector.reflect(successObs, ctx);

        assertThat(chatClient.lastRequest.getMessages().get(0).getContent())
                .startsWith("CUSTOM PROMPT:");
    }

    @Test
    @DisplayName("GatewayRequest has correct caller and low temperature")
    void gatewayRequestStructure() {
        chatClient.setContent("CONTINUE");

        reflector.reflect(successObs, ctx);

        assertThat(chatClient.lastRequest.getCaller()).isEqualTo("agent.reflector");
        assertThat(chatClient.lastRequest.getOptions().getTemperature()).isEqualTo(0.1);
        assertThat(chatClient.lastRequest.getOptions().getMaxTokens()).isEqualTo(10);
        assertThat(chatClient.lastRequest.getOptions().isEnableFunctionCalling()).isFalse();
    }

    @Nested
    @DisplayName("parseDecision")
    class ParseDecisionTest {

        @Test
        @DisplayName("COMPLETE detected case-insensitively")
        void completeCaseInsensitive() {
            assertThat(LLMReflector.parseDecision("complete")).isEqualTo(Decision.COMPLETE);
            assertThat(LLMReflector.parseDecision("Complete.")).isEqualTo(Decision.COMPLETE);
        }

        @Test
        @DisplayName("RETRY detected")
        void retryDetected() {
            assertThat(LLMReflector.parseDecision(" retry ")).isEqualTo(Decision.RETRY);
        }

        @Test
        @DisplayName("default to CONTINUE for unknown text")
        void defaultToContinue() {
            assertThat(LLMReflector.parseDecision("MAYBE")).isEqualTo(Decision.CONTINUE);
            assertThat(LLMReflector.parseDecision("")).isEqualTo(Decision.CONTINUE);
            assertThat(LLMReflector.parseDecision(null)).isEqualTo(Decision.CONTINUE);
        }
    }

    @Nested
    @DisplayName("ReflectorPromptStrategy")
    class PromptStrategyTest {

        @Test
        @DisplayName("default strategy includes goal and observation")
        void defaultStrategy() {
            var strategy = ReflectorPromptStrategy.defaults();
            ctx.incrementStep();

            String prompt = strategy.buildReflectPrompt(successObs, ctx);

            assertThat(prompt).contains("analyze AI chip market");
            assertThat(prompt).contains("found relevant data");
            assertThat(prompt).contains("SUCCESS");
            assertThat(prompt).contains("CONTINUE");
            assertThat(prompt).contains("RETRY");
            assertThat(prompt).contains("COMPLETE");
        }
    }

    // ——— Stub ———

    static class StubChatClient implements UnifiedChatClient {
        String content = "CONTINUE";
        boolean throwOnCall;
        GatewayRequest lastRequest;

        void setContent(String c) { this.content = c; }
        void setThrowOnCall(boolean v) { this.throwOnCall = v; }

        @Override
        public GatewayResponse chat(GatewayRequest request) {
            this.lastRequest = request;
            if (throwOnCall) {
                throw new RuntimeException("chat unavailable");
            }
            return new GatewayResponse(content);
        }

        @Override
        public Flux<StreamPacket> chatStream(GatewayRequest request) {
            throw new UnsupportedOperationException("stream not used in reflector");
        }
    }
}
