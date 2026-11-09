package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepRecordTest {

    private final TaskStep toolStep = TaskStep.pending("search", "广度搜索", "web_search", List.of());
    private final TaskStep llmStep = TaskStep.pending("analyze", "深度分析", "llm", List.of());

    @Test
    void recordsSuccessWithToolCallAndDuration() {
        long started = System.currentTimeMillis() - 150;
        StepExecutorResult ok = StepExecutorResult.ok("搜索结果若干", 10, 100L);

        StepRecord record = StepRecord.from("task-1", toolStep, 0,
                Map.of("keyword", "AI 新闻"), ok, 1, started);

        assertThat(record.taskId()).isEqualTo("task-1");
        assertThat(record.stepId()).isEqualTo("search");
        assertThat(record.stepIndex()).isZero();
        assertThat(record.status()).isEqualTo("COMPLETED");
        assertThat(record.output()).isEqualTo("搜索结果若干");
        assertThat(record.toolCalls()).containsExactly("web_search");
        assertThat(record.retryCount()).isEqualTo(1);
        assertThat(record.durationMs()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void recordsFailureWithErrorAndNoOutput() {
        StepExecutorResult fail = StepExecutorResult.fail("搜索超时", 50L);

        StepRecord record = StepRecord.from("task-1", toolStep, 1,
                Map.of("keyword", "AI 新闻"), fail, 3, System.currentTimeMillis() - 50);

        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.output()).isNull();
        assertThat(record.error()).isEqualTo("搜索超时");
        assertThat(record.retryCount()).isEqualTo(3);
    }

    @Test
    void llmExecutorHasNoToolCall() {
        StepExecutorResult ok = StepExecutorResult.ok("分析结论", 10, 100L);

        StepRecord record = StepRecord.from("task-1", llmStep, 0, Map.of(), ok, 1,
                System.currentTimeMillis() - 100);

        assertThat(record.status()).isEqualTo("COMPLETED");
        assertThat(record.toolCalls()).isEmpty();
    }

    @Test
    void truncatesLongInputsAndOutput() {
        String longInput = "k".repeat(600);
        String longOutput = "o".repeat(9000);
        StepExecutorResult ok = StepExecutorResult.ok(longOutput, 10, 100L);

        StepRecord record = StepRecord.from("task-1", llmStep, 0,
                Map.of("longKey", longInput), ok, 1, System.currentTimeMillis() - 100);

        assertThat(record.inputs().get("longKey")).hasSize(500);
        assertThat(record.output()).hasSize(8000);
    }

    @Test
    void nullInputsDegradesToEmpty() {
        StepExecutorResult ok = StepExecutorResult.ok("out", 10, 100L);

        StepRecord record = StepRecord.from("task-1", llmStep, 0, null, ok, 1,
                System.currentTimeMillis() - 100);

        assertThat(record.inputs()).isEmpty();
    }
}
