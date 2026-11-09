package com.yin.ai.platform.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LifecycleState")
class LifecycleStateTest {

    @Test
    @DisplayName("CREATED → LOADING is allowed")
    void createdToLoading() {
        assertThat(LifecycleState.CREATED.isTransitionAllowed(LifecycleState.LOADING)).isTrue();
    }

    @Test
    @DisplayName("LOADING → ACTIVE is allowed")
    void loadingToActive() {
        assertThat(LifecycleState.LOADING.isTransitionAllowed(LifecycleState.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("LOADING → RECOVERING is allowed")
    void loadingToRecovering() {
        assertThat(LifecycleState.LOADING.isTransitionAllowed(LifecycleState.RECOVERING)).isTrue();
    }

    @Test
    @DisplayName("ACTIVE → CONSOLIDATING is allowed")
    void activeToConsolidating() {
        assertThat(LifecycleState.ACTIVE.isTransitionAllowed(LifecycleState.CONSOLIDATING)).isTrue();
    }

    @Test
    @DisplayName("ACTIVE → COMPRESSING is allowed")
    void activeToCompressing() {
        assertThat(LifecycleState.ACTIVE.isTransitionAllowed(LifecycleState.COMPRESSING)).isTrue();
    }

    @Test
    @DisplayName("ACTIVE → ARCHIVED is allowed")
    void activeToArchived() {
        assertThat(LifecycleState.ACTIVE.isTransitionAllowed(LifecycleState.ARCHIVED)).isTrue();
    }

    @Test
    @DisplayName("ACTIVE → DELETED is allowed")
    void activeToDeleted() {
        assertThat(LifecycleState.ACTIVE.isTransitionAllowed(LifecycleState.DELETED)).isTrue();
    }

    @Test
    @DisplayName("CONSOLIDATING → ACTIVE is allowed")
    void consolidatingToActive() {
        assertThat(LifecycleState.CONSOLIDATING.isTransitionAllowed(LifecycleState.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("COMPRESSING → ACTIVE is allowed")
    void compressingToActive() {
        assertThat(LifecycleState.COMPRESSING.isTransitionAllowed(LifecycleState.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("RECOVERING → ACTIVE is allowed")
    void recoveringToActive() {
        assertThat(LifecycleState.RECOVERING.isTransitionAllowed(LifecycleState.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("ARCHIVED → DELETED is allowed")
    void archivedToDeleted() {
        assertThat(LifecycleState.ARCHIVED.isTransitionAllowed(LifecycleState.DELETED)).isTrue();
    }

    @Test
    @DisplayName("DELETED → anything is NOT allowed")
    void deletedNoTransition() {
        for (LifecycleState target : LifecycleState.values()) {
            assertThat(LifecycleState.DELETED.isTransitionAllowed(target))
                    .as("DELETED → " + target)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("CREATED → ACTIVE (skip LOADING) is NOT allowed")
    void createdDirectToActive() {
        assertThat(LifecycleState.CREATED.isTransitionAllowed(LifecycleState.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("ARCHIVED and DELETED are terminal")
    void terminalStates() {
        assertThat(LifecycleState.ARCHIVED.isTerminal()).isTrue();
        assertThat(LifecycleState.DELETED.isTerminal()).isTrue();
        assertThat(LifecycleState.ACTIVE.isTerminal()).isFalse();
        assertThat(LifecycleState.CREATED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Only ACTIVE is mutable")
    void mutableStates() {
        assertThat(LifecycleState.ACTIVE.isMutable()).isTrue();
        assertThat(LifecycleState.CREATED.isMutable()).isFalse();
        assertThat(LifecycleState.ARCHIVED.isMutable()).isFalse();
    }
}
