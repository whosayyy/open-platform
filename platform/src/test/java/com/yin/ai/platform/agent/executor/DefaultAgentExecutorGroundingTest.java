package com.yin.ai.platform.agent.executor;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.trace.StepTraceRecorder;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.core.rag.RetrievedDocument;
import com.yin.ai.platform.rag.grounding.RagGroundingGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAgentExecutorGroundingTest {

    @Test
    void regeneratesFinalAnswerBeforeReturningIt() {
        FunctionRegistry functions = mock(FunctionRegistry.class);
        UnifiedChatClient chatClient = mock(UnifiedChatClient.class);
        StepTraceRecorder trace = mock(StepTraceRecorder.class);
        RagGroundingGuard guard = mock(RagGroundingGuard.class);
        when(guard.enabled()).thenReturn(true);
        when(guard.maxRegenerationAttempts()).thenReturn(1);
        when(guard.validate(any(), any(), any(), anyBoolean()))
                .thenReturn(new RagGroundingGuard.ValidationResult(
                                false, 0.5, "missing_evidence_citation", Set.of()),
                        new RagGroundingGuard.ValidationResult(true, 0.99, "", Set.of(1)));
        when(chatClient.chat(any()))
                .thenReturn(new GatewayResponse("未经引用的答案"),
                        new GatewayResponse("经过证据支持的答案。[E1]"));

        DefaultAgentExecutor executor = new DefaultAgentExecutor(
                functions, chatClient, trace, guard);
        AgentContext context = AgentContext.from(AgentTask.of("问题"));
        context.putVariable("agent.finalStep", true);
        context.putVariable("agent.ragTriggered", true);
        context.putVariable("agent.ragEvidenceSufficient", true);
        context.putVariable("agent.ragContext", "### [E1] 证据");
        context.putVariable("agent.ragDocuments", List.of(new RetrievedDocument(
                "d1", "证据", "事实", 9.0, "rerank", Map.of())));

        StepResult result = executor.execute(Step.of("1", "回答问题"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("经过证据支持的答案。[E1]");
        verify(chatClient, times(2)).chat(any());
    }

    @Test
    void refusesWithoutRegenerationWhenRetrievalIsInsufficient() {
        FunctionRegistry functions = mock(FunctionRegistry.class);
        UnifiedChatClient chatClient = mock(UnifiedChatClient.class);
        RagGroundingGuard guard = mock(RagGroundingGuard.class);
        when(guard.enabled()).thenReturn(true);
        when(guard.maxRegenerationAttempts()).thenReturn(1);
        when(guard.refusal()).thenReturn("证据不足，无法可靠回答。");
        when(guard.validate(any(), any(), any(), anyBoolean())).thenReturn(
                new RagGroundingGuard.ValidationResult(
                        false, 0.0, "insufficient_retrieved_evidence", Set.of()));
        when(chatClient.chat(any())).thenReturn(new GatewayResponse("模型自行猜测的答案"));

        DefaultAgentExecutor executor = new DefaultAgentExecutor(
                functions, chatClient, StepTraceRecorder.NOOP, guard);
        AgentContext context = AgentContext.from(AgentTask.of("问题"));
        context.putVariable("agent.finalStep", true);
        context.putVariable("agent.ragTriggered", true);
        context.putVariable("agent.ragEvidenceSufficient", false);

        StepResult result = executor.execute(Step.of("1", "回答问题"), context);

        assertThat(result.output()).isEqualTo("证据不足，无法可靠回答。");
        verify(chatClient, times(1)).chat(any());
    }
}
