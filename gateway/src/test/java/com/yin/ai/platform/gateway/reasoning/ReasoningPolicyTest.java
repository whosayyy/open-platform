package com.yin.ai.platform.gateway.reasoning;

import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.core.agent.pattern.ReasoningMode;
import com.yin.ai.platform.core.agent.pattern.StepType;
import com.yin.ai.platform.gateway.model.ModelCapabilities;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningPolicyTest {

    private final ReasoningPolicy policy = new ReasoningPolicy();

    @Test
    void directAutoUsesNoReasoning() {
        var decision = policy.resolve(AgentMode.DIRECT, StepType.CONTENT_GENERATION,
                ReasoningMode.AUTO, ModelCapabilities.reasoning(
                        EnumSet.of(ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH), 64_000));
        assertThat(decision.effectiveReasoning()).isEqualTo(ReasoningMode.OFF);
        assertThat(decision.fallbackReason()).isNull();
    }

    @Test
    void unsupportedHighDowngradesAndExplainsFallback() {
        var decision = policy.resolve(AgentMode.DEEP_RESEARCH, StepType.EVIDENCE_ANALYSIS,
                ReasoningMode.HIGH, ModelCapabilities.reasoning(
                        EnumSet.of(ReasoningMode.LOW), 32_000));
        assertThat(decision.effectiveReasoning()).isEqualTo(ReasoningMode.LOW);
        assertThat(decision.fallbackReason()).contains("HIGH").contains("LOW");
    }

    @Test
    void legacySimpleDeserializesAsDirect() {
        assertThat(AgentMode.fromString("SIMPLE")).isEqualTo(AgentMode.DIRECT);
        assertThat(AgentMode.fromString("chat")).isEqualTo(AgentMode.DIRECT);
        assertThat(AgentMode.fromString("direct")).isEqualTo(AgentMode.DIRECT);
    }
}
