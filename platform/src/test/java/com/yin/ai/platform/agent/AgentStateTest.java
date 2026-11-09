package com.yin.ai.platform.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentState")
class AgentStateTest {

    @Test
    @DisplayName("COMPLETED, FAILED, CANCELLED, TIMEOUT are terminal")
    void terminalStates() {
        assertThat(AgentState.COMPLETED.isTerminal()).isTrue();
        assertThat(AgentState.FAILED.isTerminal()).isTrue();
        assertThat(AgentState.CANCELLED.isTerminal()).isTrue();
        assertThat(AgentState.TIMEOUT.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("IDLE, PLANNING, ACTING, OBSERVING, REFLECTING are non-terminal")
    void nonTerminalStates() {
        assertThat(AgentState.IDLE.isTerminal()).isFalse();
        assertThat(AgentState.PLANNING.isTerminal()).isFalse();
        assertThat(AgentState.ACTING.isTerminal()).isFalse();
        assertThat(AgentState.OBSERVING.isTerminal()).isFalse();
        assertThat(AgentState.REFLECTING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("IDLE → PLANNING is allowed")
    void idleToPlanning() {
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.PLANNING)).isTrue();
    }

    @Test
    @DisplayName("PLANNING → ACTING is allowed")
    void planningToActing() {
        assertThat(AgentState.PLANNING.canTransitionTo(AgentState.ACTING)).isTrue();
    }

    @Test
    @DisplayName("PLANNING → FAILED is allowed")
    void planningToFailed() {
        assertThat(AgentState.PLANNING.canTransitionTo(AgentState.FAILED)).isTrue();
    }

    @Test
    @DisplayName("ACTING → OBSERVING is allowed")
    void actingToObserving() {
        assertThat(AgentState.ACTING.canTransitionTo(AgentState.OBSERVING)).isTrue();
    }

    @Test
    @DisplayName("ACTING → FAILED, CANCELLED, TIMEOUT are allowed")
    void actingToTerminalExits() {
        assertThat(AgentState.ACTING.canTransitionTo(AgentState.FAILED)).isTrue();
        assertThat(AgentState.ACTING.canTransitionTo(AgentState.CANCELLED)).isTrue();
        assertThat(AgentState.ACTING.canTransitionTo(AgentState.TIMEOUT)).isTrue();
    }

    @Test
    @DisplayName("OBSERVING → REFLECTING is allowed")
    void observingToReflecting() {
        assertThat(AgentState.OBSERVING.canTransitionTo(AgentState.REFLECTING)).isTrue();
    }

    @Test
    @DisplayName("REFLECTING → ACTING (retry/continue loop)")
    void reflectingToActing() {
        assertThat(AgentState.REFLECTING.canTransitionTo(AgentState.ACTING)).isTrue();
    }

    @Test
    @DisplayName("REFLECTING → COMPLETED, FAILED, CANCELLED are allowed")
    void reflectingToTerminalExits() {
        assertThat(AgentState.REFLECTING.canTransitionTo(AgentState.COMPLETED)).isTrue();
        assertThat(AgentState.REFLECTING.canTransitionTo(AgentState.FAILED)).isTrue();
        assertThat(AgentState.REFLECTING.canTransitionTo(AgentState.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("IDLE → ACTING (skip PLANNING) is NOT allowed")
    void idleDirectToActing() {
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.ACTING)).isFalse();
    }

    @Test
    @DisplayName("PLANNING → REFLECTING (skip ACTING) is NOT allowed")
    void planningToReflecting() {
        assertThat(AgentState.PLANNING.canTransitionTo(AgentState.REFLECTING)).isFalse();
    }

    @Test
    @DisplayName("REFLECTING → PLANNING is NOT allowed")
    void reflectingToPlanning() {
        assertThat(AgentState.REFLECTING.canTransitionTo(AgentState.PLANNING)).isFalse();
    }

    @Test
    @DisplayName("any terminal state → any state is NOT allowed")
    void terminalNoTransition() {
        for (AgentState terminal : new AgentState[]{
                AgentState.COMPLETED, AgentState.FAILED, AgentState.CANCELLED, AgentState.TIMEOUT}) {
            for (AgentState target : AgentState.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as(terminal + " → " + target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("all legal transitions are covered in the full matrix")
    void fullTransitionMatrix() {
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.IDLE)).isFalse();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.PLANNING)).isTrue();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.ACTING)).isFalse();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.OBSERVING)).isFalse();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.REFLECTING)).isFalse();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.COMPLETED)).isFalse();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.FAILED)).isFalse();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.CANCELLED)).isFalse();
        assertThat(AgentState.IDLE.canTransitionTo(AgentState.TIMEOUT)).isFalse();

        assertThat(AgentState.ACTING.canTransitionTo(AgentState.COMPLETED)).isFalse();
        assertThat(AgentState.ACTING.canTransitionTo(AgentState.REFLECTING)).isFalse();
        assertThat(AgentState.ACTING.canTransitionTo(AgentState.PLANNING)).isFalse();

        assertThat(AgentState.OBSERVING.canTransitionTo(AgentState.COMPLETED)).isFalse();
        assertThat(AgentState.OBSERVING.canTransitionTo(AgentState.ACTING)).isFalse();

        assertThat(AgentState.REFLECTING.canTransitionTo(AgentState.OBSERVING)).isFalse();
        assertThat(AgentState.REFLECTING.canTransitionTo(AgentState.TIMEOUT)).isFalse();
    }
}
