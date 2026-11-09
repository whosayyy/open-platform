package com.yin.ai.platform.structured;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredToolGeneratorTest {

    @AfterEach
    void resetThinkingMode() {
        StructuredToolGenerator.setThinkingModeEnabled(false);
    }

    @Test
    void captureToolHasSchemaAndNoSideEffect() {
        var tool = StructuredToolGenerator.captureTool("submit", "submit result",
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string"))));
        assertThat(tool.getToolDefinition().name()).isEqualTo("submit");
        assertThat(tool.getToolDefinition().inputSchema()).contains("value");
        assertThat(tool.call("{\"value\":\"ok\"}")).isEqualTo("{\"accepted\":true}");
    }

    @Test
    void toolChoiceAndThinkingCompatibilityAreStable() {
        assertThat(StructuredToolGenerator.functionChoice("submit"))
                .containsEntry("type", "function");
        StructuredToolGenerator.setThinkingModeEnabled(true);
        assertThat(StructuredToolGenerator.isThinkingModeEnabled()).isTrue();
        assertThat(StructuredToolGenerator.isThinkingModeToolChoiceRejection(
                new IllegalArgumentException("Thinking mode does not support tool_choice"))).isTrue();
        assertThat(StructuredToolGenerator.isThinkingModeToolChoiceRejection(
                new IllegalArgumentException("other"))).isFalse();
    }
}
