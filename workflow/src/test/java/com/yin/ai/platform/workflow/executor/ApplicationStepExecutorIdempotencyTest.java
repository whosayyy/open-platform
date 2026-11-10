package com.yin.ai.platform.workflow.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.runtime.RedisStepIdempotencyStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStepExecutorIdempotencyTest {

    @Test
    void replaysCommittedResultWithoutRepeatingSideEffect() {
        AtomicInteger calls = new AtomicInteger();
        ApplicationStepHandler handler = new ApplicationStepHandler() {
            @Override
            public String name() {
                return "publish";
            }

            @Override
            public HandlerResult execute(StepExecutionContext context) {
                calls.incrementAndGet();
                return HandlerResult.ok(Map.of("contentId", 42));
            }
        };
        ApplicationStepExecutor executor = new ApplicationStepExecutor(
                new ApplicationStepHandlerRegistry(List.of(handler)),
                new RedisStepIdempotencyStore(null),
                new CompensationHandlerRegistry(List.of()),
                new ObjectMapper());

        StepExecutionContext context = context();
        StepExecutionResult first = executor.execute(context);
        StepExecutionResult replay = executor.execute(context);

        assertThat(first.success()).isTrue();
        assertThat(replay.success()).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(replay.output().content()).isEqualTo(Map.of("contentId", 42));
    }

    private StepExecutionContext context() {
        TaskRun taskRun = new TaskRun();
        taskRun.setTaskRunId("task-1");
        StepDefinition step = new StepDefinition();
        step.setId("publish-step");
        step.setHandler("publish");
        step.setOutput(ArtifactType.PUBLISHED_ARTICLE.name());
        return new StepExecutionContext(taskRun, null, step, null, List.of(), null, null, null);
    }
}
