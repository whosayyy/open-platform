package com.yin.ai.platform.agent.event;

import com.yin.ai.platform.agent.pattern.ReasoningMode;
import com.yin.ai.platform.agent.pattern.StepResult;
import com.yin.ai.platform.agent.pattern.StepSummary;
import com.yin.ai.platform.gateway.ModelExecutionOptions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventV2ContractTest {

    @Test
    void resultAndSummaryUseDifferentEventContracts() {
        StepResult result = new StepResult("t:s:1", "s", true, Map.of("documents", 5),
                "artifact://s", List.of("artifact://s"), List.of("https://example.test"),
                "context:v1", null, Instant.now());
        StepSummary summary = new StepSummary("s", "检索完成", "已获得五个来源。",
                List.of("来源已交叉核验"), "进入写作");

        AgentEvent resultEvent = AgentEvent.stepResult("t", result);
        AgentEvent summaryEvent = AgentEvent.stepSummary("t", summary);

        assertThat(resultEvent.type()).isEqualTo(TaskEventType.STEP_RESULT);
        assertThat(resultEvent.payload()).containsKey("value").doesNotContainKey("content");
        assertThat(summaryEvent.type()).isEqualTo(TaskEventType.STEP_SUMMARY);
        assertThat(summaryEvent.payload()).containsEntry("content", "已获得五个来源。");
    }

    @Test
    void reasoningLifecycleNeverContainsReasoningText() {
        var options = new ModelExecutionOptions(ReasoningMode.HIGH, ReasoningMode.MEDIUM,
                "HIGH unsupported");
        AgentEvent event = AgentEvent.reasoningCompleted("t", "s", options, 42);

        assertThat(event.payload()).containsEntry("effectiveMode", "MEDIUM")
                .containsEntry("tokensUsed", 42L)
                .doesNotContainKeys("content", "reasoning", "chainOfThought");
    }

    @Test
    void summaryDeltaContainsMarkdownChunkButNoWorkerOrReasoningFields() {
        AgentEvent event = AgentEvent.stepSummaryDelta("t", "s", "已核准两项事实", 2);

        assertThat(event.type()).isEqualTo(TaskEventType.STEP_SUMMARY_DELTA);
        assertThat(event.payload()).containsEntry("delta", "已核准两项事实")
                .containsEntry("contentType", "text/markdown")
                .doesNotContainKeys("value", "rawOutput", "reasoning", "chainOfThought");
    }
}
