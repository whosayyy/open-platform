package com.yin.ai.platform.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.workflow.event.ExecutionEvent;
import com.yin.ai.platform.workflow.event.ExecutionEventType;
import com.yin.ai.platform.workflow.event.ExecutionEventPublisher;
import com.yin.ai.platform.workflow.executor.StepExecutionContext;
import com.yin.ai.platform.workflow.executor.StepExecutionResult;
import com.yin.ai.platform.workflow.executor.StepExecutor;
import com.yin.ai.platform.workflow.executor.StepExecutorRegistry;
import com.yin.ai.platform.workflow.hitl.HumanInputDecision;
import com.yin.ai.platform.workflow.hitl.HumanInputPolicy;
import com.yin.ai.platform.workflow.load.WorkflowRegistry;
import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.ExecutorType;
import com.yin.ai.platform.workflow.model.StepCondition;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.StepRun;
import com.yin.ai.platform.workflow.model.StepStatus;
import com.yin.ai.platform.workflow.model.TaskRequest;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.TaskStatus;
import com.yin.ai.platform.workflow.model.UserInput;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import com.yin.ai.platform.workflow.prompt.StepContext;
import com.yin.ai.platform.workflow.prompt.StepContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultExecutionRuntimeTest {

    private static final HumanInputPolicy PROCEED = (task, ctx) -> HumanInputDecision.proceed();

    private WorkflowTaskStore store;
    private WorkflowRegistry workflowRegistry;
    private ExecutionEventPublisher eventPublisher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        store = new RedisWorkflowTaskStore(null, new ObjectMapper());
        workflowRegistry = mock(WorkflowRegistry.class);
        eventPublisher = mock(ExecutionEventPublisher.class);
        objectMapper = new ObjectMapper();
    }

    @Test
    void completesLinearPipelineAndSkipsPublishWhenNotExplicit() {
        WorkflowDefinition wf = workflow();
        when(workflowRegistry.get("test-writing")).thenReturn(wf);
        DefaultExecutionRuntime runtime = newRuntime(
                new StepExecutorRegistry(List.of(
                        new FakeStepExecutor(ExecutorType.LLM, Set.of()),
                        new FakeStepExecutor(ExecutorType.APPLICATION, Set.of()))),
                PROCEED);

        TaskRun task = runtime.start(wf, request(false));
        runtime.execute(task.getTaskRunId());

        TaskRun result = runtime.getTask(task.getTaskRunId());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);

        List<StepRun> runs = store.listStepRuns(task.getTaskRunId());
        assertThat(runs).hasSize(4);
        assertThat(stepRun(runs, "brief").getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(stepRun(runs, "draft").getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(stepRun(runs, "finalize").getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(stepRun(runs, "publish").getStatus()).isEqualTo(StepStatus.SKIPPED);

        List<Artifact> artifacts = store.listArtifacts(task.getTaskRunId());
        assertThat(artifacts).hasSize(3);
        assertThat(artifacts).anyMatch(a -> a.type() == ArtifactType.FINAL_RESULT
                && "body-finalize".equals(((Map<?, ?>) a.content()).get("content")));
    }

    @Test
    void executesPublishWhenExplicitPublishRequested() {
        WorkflowDefinition wf = workflow();
        when(workflowRegistry.get("test-writing")).thenReturn(wf);
        DefaultExecutionRuntime runtime = newRuntime(
                new StepExecutorRegistry(List.of(
                        new FakeStepExecutor(ExecutorType.LLM, Set.of()),
                        new FakeStepExecutor(ExecutorType.APPLICATION, Set.of()))),
                PROCEED);

        TaskRun task = runtime.start(wf, request(true));
        runtime.execute(task.getTaskRunId());

        TaskRun result = runtime.getTask(task.getTaskRunId());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(stepRun(store.listStepRuns(task.getTaskRunId()), "publish").getStatus())
                .isEqualTo(StepStatus.SUCCEEDED);
        assertThat(store.listArtifacts(task.getTaskRunId()))
                .anyMatch(a -> a.type() == ArtifactType.PUBLISHED_ARTICLE);
    }

    @Test
    void pausesOnHumanInputPolicyThenResumeCompletes() {
        WorkflowDefinition wf = workflow();
        when(workflowRegistry.get("test-writing")).thenReturn(wf);
        HumanInputPolicy pausing = (task, ctx) -> "draft".equals(ctx.step().getId())
                ? HumanInputDecision.pause("等待确认后继续") : HumanInputDecision.proceed();
        DefaultExecutionRuntime runtime = newRuntime(
                new StepExecutorRegistry(List.of(
                        new FakeStepExecutor(ExecutorType.LLM, Set.of()),
                        new FakeStepExecutor(ExecutorType.APPLICATION, Set.of()))),
                pausing);

        TaskRun task = runtime.start(wf, request(false));
        runtime.execute(task.getTaskRunId());

        TaskRun waiting = runtime.getTask(task.getTaskRunId());
        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.WAITING_INPUT);
        assertThat(waiting.getWaitingInputReason()).isEqualTo("等待确认后继续");
        assertThat(stepRun(store.listStepRuns(task.getTaskRunId()), "draft").getStatus())
                .isEqualTo(StepStatus.SUCCEEDED);
        assertThat(stepRun(store.listStepRuns(task.getTaskRunId()), "finalize").getStatus())
                .isEqualTo(StepStatus.READY);

        runtime.resume(task.getTaskRunId(), UserInput.of(task.getTaskRunId(), "继续"));

        TaskRun result = runtime.getTask(task.getTaskRunId());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.getContext().get("lastUserInput")).isEqualTo("继续");
        assertThat(stepRun(store.listStepRuns(task.getTaskRunId()), "publish").getStatus())
                .isEqualTo(StepStatus.SKIPPED);
    }

    @Test
    void pausesWhenExecutorRequestsInputThenResumeFinishes() {
        WorkflowDefinition wf = askWorkflow();
        when(workflowRegistry.get("test-ask")).thenReturn(wf);
        DefaultExecutionRuntime runtime = newRuntime(
                new StepExecutorRegistry(List.of(
                        new FakeStepExecutor(ExecutorType.LLM, Set.of()),
                        new FakeStepExecutor(ExecutorType.APPLICATION, Set.of("ask")))),
                PROCEED);

        TaskRun task = runtime.start(wf, request(false));
        runtime.execute(task.getTaskRunId());

        TaskRun waiting = runtime.getTask(task.getTaskRunId());
        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.WAITING_INPUT);
        assertThat(waiting.getWaitingInputReason()).isEqualTo("请补充必要信息");
        assertThat(stepRun(store.listStepRuns(task.getTaskRunId()), "ask").getStatus())
                .isEqualTo(StepStatus.SUCCEEDED);

        runtime.resume(task.getTaskRunId(), UserInput.of(task.getTaskRunId(), "已补充"));

        assertThat(runtime.getTask(task.getTaskRunId()).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void emitsPlanStepSummaryAndFinalArtifactLifecycleInOrder() {
        WorkflowDefinition wf = workflow();
        when(workflowRegistry.get("test-writing")).thenReturn(wf);
        DefaultExecutionRuntime runtime = newRuntime(
                new StepExecutorRegistry(List.of(
                        new FakeStepExecutor(ExecutorType.LLM, Set.of()),
                        new FakeStepExecutor(ExecutorType.APPLICATION, Set.of()))),
                PROCEED);

        TaskRun task = runtime.start(wf, request(false));
        runtime.execute(task.getTaskRunId());

        org.mockito.ArgumentCaptor<ExecutionEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ExecutionEvent.class);
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.atLeastOnce())
                .publish(captor.capture());
        List<ExecutionEventType> types = captor.getAllValues().stream()
                .map(ExecutionEvent::type)
                .toList();

        assertThat(types).contains(ExecutionEventType.PLAN_CREATED,
                ExecutionEventType.STEP_SUMMARY,
                ExecutionEventType.ARTIFACT_STARTED,
                ExecutionEventType.ARTIFACT_DELTA,
                ExecutionEventType.ARTIFACT_COMPLETED,
                ExecutionEventType.TASK_SUMMARY,
                ExecutionEventType.TASK_COMPLETED);
        assertThat(types.indexOf(ExecutionEventType.ARTIFACT_STARTED))
                .isLessThan(types.indexOf(ExecutionEventType.ARTIFACT_DELTA));
        assertThat(types.indexOf(ExecutionEventType.ARTIFACT_DELTA))
                .isLessThan(types.indexOf(ExecutionEventType.ARTIFACT_COMPLETED));
        assertThat(types.indexOf(ExecutionEventType.TASK_SUMMARY))
                .isLessThan(types.indexOf(ExecutionEventType.TASK_COMPLETED));
    }

    private DefaultExecutionRuntime newRuntime(StepExecutorRegistry registry, HumanInputPolicy policy) {
        ObjectProvider<HumanInputPolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(policy);
        StepContextBuilder builder = mock(StepContextBuilder.class);
        when(builder.build(any(), any(), any(), any(), any()))
                .thenReturn(new StepContext("system", "user", Set.of(), Map.of()));
        return new DefaultExecutionRuntime(store, workflowRegistry, new DefaultStepScheduler(),
                new RetryManager(), registry, eventPublisher, builder, provider, objectMapper);
    }

    private TaskRequest request(boolean explicitPublish) {
        return TaskRequest.builder()
                .goal("写一篇深度报道")
                .userId(7L)
                .conversationId("conv-1")
                .explicitPublishRequested(explicitPublish)
                .build();
    }

    private WorkflowDefinition workflow() {
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setName("test-writing");
        wf.setVersion("1.0.0");
        wf.setSteps(List.of(
                step("brief", ExecutorType.LLM, List.of(), List.of(), "WRITING_BRIEF", null),
                step("draft", ExecutorType.LLM, List.of("brief"), List.of("WRITING_BRIEF"), "ARTICLE_DRAFT", null),
                step("finalize", ExecutorType.APPLICATION, List.of("draft"), List.of("ARTICLE_DRAFT"),
                        "FINAL_RESULT", null),
                step("publish", ExecutorType.APPLICATION, List.of("finalize"), List.of("FINAL_RESULT"),
                        "PUBLISHED_ARTICLE", new StepCondition("explicit_publish", "explicit_publish"))));
        return wf;
    }

    private WorkflowDefinition askWorkflow() {
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setName("test-ask");
        wf.setVersion("1.0.0");
        wf.setSteps(List.of(
                step("brief", ExecutorType.LLM, List.of(), List.of(), "WRITING_BRIEF", null),
                step("ask", ExecutorType.APPLICATION, List.of("brief"), List.of("WRITING_BRIEF"),
                        "FINAL_RESULT", null)));
        return wf;
    }

    private StepDefinition step(String id, ExecutorType type, List<String> dependsOn,
                               List<String> input, String output, StepCondition condition) {
        StepDefinition def = new StepDefinition();
        def.setId(id);
        def.setName(id);
        def.setExecutorType(type);
        def.setDependsOn(dependsOn);
        def.setInput(input);
        def.setOutput(output);
        def.setCondition(condition);
        return def;
    }

    private StepRun stepRun(List<StepRun> runs, String stepId) {
        return runs.stream().filter(r -> r.getStepId().equals(stepId)).findFirst().orElseThrow();
    }

    /** 伪执行器：默认成功产出结构化产物；needInputStepIds 内的步骤返回等待输入。 */
    private static class FakeStepExecutor implements StepExecutor {

        private final ExecutorType type;
        private final Set<String> needInputStepIds;

        FakeStepExecutor(ExecutorType type, Set<String> needInputStepIds) {
            this.type = type;
            this.needInputStepIds = needInputStepIds;
        }

        @Override
        public ExecutorType type() {
            return type;
        }

        @Override
        public StepExecutionResult execute(StepExecutionContext context) {
            String stepId = context.step().getId();
            if (needInputStepIds.contains(stepId)) {
                return StepExecutionResult.needInput("请补充必要信息");
            }
            ArtifactType outputType = context.step().outputType();
            Artifact artifact = Artifact.of(context.taskRun().getTaskRunId(), stepId, outputType,
                    Map.of("content", "body-" + stepId));
            return StepExecutionResult.ok(artifact);
        }
    }
}
