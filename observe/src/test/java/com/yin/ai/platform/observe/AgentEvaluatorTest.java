package com.yin.ai.platform.observe;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.StreamPacket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link AgentEvaluator} 的工具调用式 LLM 评测 + 启发式降级：
 * 工具参数（submitEvalScores）优先，LLM 不可用/解析失败回退启发式规则。
 */
@DisplayName("AgentEvaluator")
class AgentEvaluatorTest {

    private static final String GOAL = "分析AI芯片市场趋势";
    private static final String OUTPUT = "AI芯片市场正快速扩张，多家厂商加大投入，前景看好。";

    @SuppressWarnings("unchecked")
    private ObjectProvider<UnifiedChatClient> provider(GatewayResponse response) {
        ObjectProvider<UnifiedChatClient> provider = mock(ObjectProvider.class);
        if (response == null) {
            when(provider.getIfAvailable()).thenReturn(null);
        } else {
            when(provider.getIfAvailable()).thenReturn(new StubChatClient(response));
        }
        return provider;
    }

    @Test
    @DisplayName("工具参数有效 → 使用 LLM 评分并计数")
    void validToolArgumentsUseLlmScores() {
        GatewayResponse response = new GatewayResponse();
        response.setToolCallArguments(
                "{\"relevance\":0.9,\"completeness\":0.8,\"safety\":1.0,\"brief\":\"good\"}");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentEvaluator evaluator = new AgentEvaluator(registry, provider(response));

        AgentEvaluator.EvaluationResult result = evaluator.evaluate(GOAL, OUTPUT, "");

        assertThat(result.relevance()).isEqualTo(0.9);
        assertThat(result.completeness()).isEqualTo(0.8);
        assertThat(result.safety()).isEqualTo(1.0);
        assertThat(result.overall()).isCloseTo(0.89, org.assertj.core.data.Offset.offset(0.001));
        assertThat(registry.get("ai.agent.evaluation.llm").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("无工具参数 → 回退 content JSON 解析")
    void contentFallbackWhenNoToolArgs() {
        GatewayResponse response = new GatewayResponse(
                "{\"relevance\":0.7,\"completeness\":0.6,\"safety\":0.9,\"brief\":\"ok\"}");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentEvaluator evaluator = new AgentEvaluator(registry, provider(response));

        AgentEvaluator.EvaluationResult result = evaluator.evaluate(GOAL, OUTPUT, "");

        assertThat(result.relevance()).isEqualTo(0.7);
        assertThat(result.completeness()).isEqualTo(0.6);
        assertThat(result.safety()).isEqualTo(0.9);
        assertThat(registry.get("ai.agent.evaluation.llm").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("响应无法解析 → 回退启发式规则并计数")
    void invalidResponseFallsBackToHeuristic() {
        GatewayResponse response = new GatewayResponse("无法解析的内容");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentEvaluator evaluator = new AgentEvaluator(registry, provider(response));

        AgentEvaluator.EvaluationResult result = evaluator.evaluate(GOAL, OUTPUT, "");

        assertThat(result.overall()).isBetween(0.0, 1.0);
        assertThat(registry.get("ai.agent.evaluation.heuristic_fallback").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LLM 客户端不可用 → 回退启发式规则")
    void nullClientFallsBackToHeuristic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentEvaluator evaluator = new AgentEvaluator(registry, provider(null));

        AgentEvaluator.EvaluationResult result = evaluator.evaluate(GOAL, OUTPUT, "");

        assertThat(result.overall()).isBetween(0.0, 1.0);
        assertThat(registry.get("ai.agent.evaluation.heuristic_fallback").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LLM 返回低安全性 → 计数安全违规")
    void lowSafetyIncrementsViolationCounter() {
        GatewayResponse response = new GatewayResponse();
        response.setToolCallArguments(
                "{\"relevance\":0.8,\"completeness\":0.7,\"safety\":0.3,\"brief\":\"风险\"}");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentEvaluator evaluator = new AgentEvaluator(registry, provider(response));

        evaluator.evaluate(GOAL, OUTPUT, "");

        assertThat(registry.get("ai.agent.evaluation.safety_violation").counter().count())
                .isEqualTo(1);
    }

    private static class StubChatClient implements UnifiedChatClient {
        private final GatewayResponse response;

        StubChatClient(GatewayResponse response) {
            this.response = response;
        }

        @Override
        public GatewayResponse chat(GatewayRequest request) {
            return response;
        }

        @Override
        public Flux<StreamPacket> chatStream(GatewayRequest request) {
            throw new UnsupportedOperationException("stream not used in evaluator");
        }
    }
}
