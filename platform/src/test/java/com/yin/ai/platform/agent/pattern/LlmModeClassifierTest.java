package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmModeClassifierTest {

    @Test
    void mapsStructuredDecisionsToAgentModes() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"mode\":\"PLAN_EXECUTE\",\"reason\":\"writing\"}"));
        LlmModeClassifier classifier = new LlmModeClassifier(client);

        assertThat(classifier.classify("write a report", RouteContext.of("u1", "s1")))
                .hasValue(AgentMode.PLAN_EXECUTE);
    }

    @Test
    void invalidOrFailedDecisionDefersToRouter() {
        StructuredClient client = mock(StructuredClient.class);
        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of("{\"mode\":\"\"}"));
        assertThat(new LlmModeClassifier(client).classify("ambiguous", RouteContext.of("u1", "s1")))
                .isEmpty();

        when(client.generate(anyString(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("provider down"));
        assertThat(new LlmModeClassifier(client).classify("ambiguous", RouteContext.of("u1", "s1")))
                .isEmpty();
    }

    @Test
    void blankInputIsDirectWithoutCallingProvider() {
        LlmModeClassifier classifier = new LlmModeClassifier(mock(StructuredClient.class));
        assertThat(classifier.classify("", RouteContext.of("u1", "s1"))).hasValue(AgentMode.DIRECT);
        assertThat(classifier.classify(null, RouteContext.of("u1", "s1"))).hasValue(AgentMode.DIRECT);
    }
}
