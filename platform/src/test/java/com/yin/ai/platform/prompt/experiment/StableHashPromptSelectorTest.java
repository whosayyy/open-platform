package com.yin.ai.platform.prompt.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StableHashPromptSelectorTest {

    private final PromptSelector selector = new StableHashPromptSelector();

    @Test
    void assignmentIsStickyForSameIdentity() {
        PromptExperiment experiment = experiment();
        PromptSelectionContext context = new PromptSelectionContext("t1", "u1", "s1");

        assertThat(selector.select(experiment, context))
                .isEqualTo(selector.select(experiment, context));
    }

    @Test
    void disabledExperimentUsesDefaultVersion() {
        PromptExperiment experiment = new PromptExperiment("chat", false, "1.0", List.of());
        PromptSelection selection = selector.select(experiment,
                new PromptSelectionContext("t1", "u1", "s1"));
        assertThat(selection.fallback()).isTrue();
        assertThat(selection.promptVersion()).isEqualTo("1.0");
    }

    @Test
    void enabledWeightsMustTotalTenThousand() {
        assertThatThrownBy(() -> new PromptExperiment("chat", true, "1.0",
                List.of(new PromptVariant("a", "1.1", 9_999))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void selectedBucketFallsInsideConfiguredVariant() {
        PromptSelection selection = selector.select(experiment(),
                new PromptSelectionContext("tenant", "user", "session"));
        assertThat(selection.bucket()).isBetween(0, 9_999);
        assertThat(selection.variantId()).isEqualTo(selection.bucket() < 5_000 ? "control" : "candidate");
    }

    private PromptExperiment experiment() {
        return new PromptExperiment("chat-exp", true, "1.0", List.of(
                new PromptVariant("control", "1.0", 5_000),
                new PromptVariant("candidate", "1.1", 5_000)));
    }
}
