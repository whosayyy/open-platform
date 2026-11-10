package com.yin.ai.platform.agent.pattern.reflect;

import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmStepReflectorTest {

    private static final String LONG_OUTPUT = "A sufficiently detailed execution result ".repeat(8);
    private AgentExecutionContext context;

    @BeforeEach
    void setUp() {
        context = new AgentExecutionContext("t1", "s1", "u1", AgentMode.REACT,
                null, mock(EventPublisher.class), mock(ContextStore.class));
    }

    @Test
    void deterministicGuardRetriesBlankAndShortOutput() {
        LlmStepReflector reflector = new LlmStepReflector(mock(StructuredClient.class));
        TaskStep step = TaskStep.pending("s1", "analyze", "llm", List.of());
        assertThat(reflector.reflect(step, "", context).decision()).isEqualTo(ReflectDecision.RETRY);
        assertThat(reflector.reflect(step, "short", context).decision()).isEqualTo(ReflectDecision.RETRY);
    }

    @Test
    void mapsContinueRetryAndSkipDecisions() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"decision\":\"RETRY\",\"reason\":\"weak\",\"hint\":\"add sources\"}"));
        LlmStepReflector reflector = new LlmStepReflector(client);
        TaskStep step = TaskStep.pending("s1", "analyze", "llm", List.of());
        ReflectResult result = reflector.reflect(step, LONG_OUTPUT, context);
        assertThat(result.decision()).isEqualTo(ReflectDecision.RETRY);
        assertThat(result.retryHint()).isEqualTo("add sources");

        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"decision\":\"SKIP_TO_SYNTHESIS\",\"reason\":\"enough\"}"));
        assertThat(reflector.reflect(step, LONG_OUTPUT, context).decision())
                .isEqualTo(ReflectDecision.SKIP_TO_SYNTHESIS);
    }

    @Test
    void replanSanitizesNewStepsAgainstAvailableExecutors() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("""
                        {"decision":"REPLAN","reason":"more research","newSteps":[
                          {"id":"research","description":"research","executor":"web_search","dependsOn":["s1"]}
                        ]}
                        """));
        LlmStepReflector reflector = new LlmStepReflector(client,
                executor -> "llm".equals(executor) || "web_search".equals(executor));
        ReflectResult result = reflector.reflect(
                TaskStep.pending("s1", "analyze", "llm", List.of()), LONG_OUTPUT, context);
        assertThat(result.decision()).isEqualTo(ReflectDecision.REPLAN);
        assertThat(result.newSteps()).singleElement().satisfies(step -> {
            assertThat(step.executor()).isEqualTo("web_search");
            assertThat(step.dependsOn()).containsExactly("s1");
        });
    }

    @Test
    void providerFailureDefaultsToContinue() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("provider down"));
        ReflectResult result = new LlmStepReflector(client).reflect(
                TaskStep.pending("s1", "analyze", "llm", List.of()), LONG_OUTPUT, context);
        assertThat(result.decision()).isEqualTo(ReflectDecision.CONTINUE);
    }
}
