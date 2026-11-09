package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import com.yin.ai.platform.rollout.RolloutMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DefaultToolSelectionPolicyTest {

    @Test
    void smallCandidateSetUsesDeterministicSelection() {
        AtomicInteger calls = new AtomicInteger();
        ToolCandidateSelector selector = (goal, candidates, context) -> {
            calls.incrementAndGet();
            return Set.of("tool-1");
        };
        DefaultToolSelectionPolicy policy = policy(selector, RolloutMode.ENFORCE);

        ToolSelectionPolicy.Selection result = policy.select("goal", tools(8), Set.of(), Set.of(),
                mock(AgentContext.class));

        assertThat(result.strategy()).isEqualTo("deterministic");
        assertThat(result.tools()).hasSize(8);
        assertThat(calls).hasValue(0);
    }

    @Test
    void llmSelectionCannotExpandAuthorizedCandidates() {
        DefaultToolSelectionPolicy policy = policy(
                (goal, candidates, context) -> Set.of("tool-2", "unauthorized"), RolloutMode.ENFORCE);
        Set<String> declared = IntStream.rangeClosed(1, 9)
                .mapToObj(i -> "tool-" + i).collect(java.util.stream.Collectors.toSet());

        ToolSelectionPolicy.Selection result = policy.select(
                "goal", tools(10), declared, Set.of(), mock(AgentContext.class));

        assertThat(result.tools()).extracting(FunctionDefinition::getName).containsExactly("tool-2");
        assertThat(result.strategy()).isEqualTo("llm");
    }

    @Test
    void selectorFailureFallsBackOnlyToSkillDeclaredTools() {
        DefaultToolSelectionPolicy policy = policy((goal, candidates, context) -> {
            throw new IllegalStateException("unavailable");
        }, RolloutMode.ENFORCE);
        Set<String> declared = IntStream.rangeClosed(1, 9)
                .mapToObj(i -> "tool-" + i).collect(java.util.stream.Collectors.toSet());

        ToolSelectionPolicy.Selection result = policy.select(
                "goal", tools(10), declared, Set.of(), mock(AgentContext.class));

        assertThat(result.fallback()).isTrue();
        assertThat(result.tools()).extracting(FunctionDefinition::getName)
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    private DefaultToolSelectionPolicy policy(ToolCandidateSelector selector, RolloutMode mode) {
        RoadmapFeatureProperties rollout = new RoadmapFeatureProperties();
        rollout.setAgentConstraints(mode);
        return new DefaultToolSelectionPolicy(selector, rollout, new SimpleMeterRegistry());
    }

    private List<FunctionDefinition> tools(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> FunctionDefinition.builder().name("tool-" + i).build())
                .toList();
    }
}
