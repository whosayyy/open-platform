package com.yin.ai.platform.core.agent.checkpoint;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCheckpointTest {

    @Test
    void roundTripsOnlyPortableStateWithoutImplementationTypes() {
        AgentCheckpoint checkpoint = new AgentCheckpoint("task-1", "PLAN_EXECUTE",
                "executing", 3, Map.of("plan", Map.of("steps", List.of(
                        Map.of("id", "s1", "completed", true)))), Instant.now());

        AgentCheckpoint restored = AgentCheckpoint.fromPortableMap(
                checkpoint.toPortableMap());

        assertThat(restored).isEqualTo(checkpoint);
        assertThat(restored.toPortableMap().toString())
                .doesNotContain("AgentPlan", "PlanStep", "com.yin");
    }

    @Test
    void rejectsInfrastructureOrDomainObjectsInState() {
        assertThatThrownBy(() -> new AgentCheckpoint("task-1", "PLAN_EXECUTE",
                "executing", 1, Map.of("invalid", new Object()), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported checkpoint value");
    }
}
