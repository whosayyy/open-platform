package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.pattern.context.ContextManager;
import com.yin.ai.platform.agent.pattern.executor.StepExecutor;
import com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher;
import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.reflect.ReflectResult;
import com.yin.ai.platform.agent.pattern.reflect.StepReflector;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.gateway.ModelExecutionOptions;
import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.structured.StructuredClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanExecuteV2LoopTest {

    @Test
    void fiveStepsEmitResultThenSummaryThenCompletedAndAdvanceAutomatically() {
        GatewayTextClient client = mock(GatewayTextClient.class);
        when(client.generate(anyString(), anyString(), nullable(String.class), nullable(Double.class),
                nullable(ModelExecutionOptions.class))).thenReturn(new GatewayResponse("""
                [
                  {"id":"s1","description":"step one","executor":"llm","dependsOn":[]},
                  {"id":"s2","description":"step two","executor":"llm","dependsOn":[]},
                  {"id":"s3","description":"step three","executor":"llm","dependsOn":[]},
                  {"id":"s4","description":"step four","executor":"llm","dependsOn":[]},
                  {"id":"s5","description":"step five","executor":"llm","dependsOn":[]}
                ]
                """));
        when(client.stream(anyString(), anyString(), nullable(String.class), nullable(Double.class),
                nullable(ModelExecutionOptions.class))).thenReturn(Flux.just(
                StreamPacket.builder().type("text").content("### Step complete\n\n").build(),
                StreamPacket.builder().type("text").content("- Result is available.\n").build()));

        StepExecutor executor = new StepExecutor() {
            @Override public String executorPrefix() { return "llm"; }
            @Override public StepExecutorResult execute(String executorId, Map<String, String> inputs,
                                                        AgentExecutionContext ctx) {
                return StepExecutorResult.ok("result-" + ctx.getCurrentStepId(), 10, 1);
            }
        };
        StepExecutorDispatcher dispatcher = new StepExecutorDispatcher(List.of(executor));
        StepReflector reflector = (step, output, ctx) -> ReflectResult.continueExecution();
        PlanExecuteAgentPattern pattern = new PlanExecuteAgentPattern(
                client, mock(StructuredClient.class), dispatcher, new ContextManager(client), reflector);

        ContextStore contextStore = mock(ContextStore.class);
        EventPublisher publisher = mock(EventPublisher.class);
        AgentExecutionContext context = new AgentExecutionContext(
                "task-5", "session-5", "7", AgentMode.PLAN_EXECUTE,
                AgentContext.from(new AgentTask("analyze dataset", Map.of(), null, null)),
                publisher, contextStore, ignored -> { },
                (taskId, stepId, stepIndex, content) -> new StepArtifactStore.ArtifactRef(
                        stepId + ".md", "agent/" + taskId + "/" + stepId + ".md", null,
                        content == null ? 0 : content.length()));
        TaskEngine taskEngine = mock(TaskEngine.class);
        when(taskEngine.tryAcquireStepExecution(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        context.bindTaskEngine(taskEngine);

        List<AgentEvent> events = pattern.execute(context).collectList().block();

        assertThat(events).isNotNull();
        for (int i = 1; i <= 5; i++) {
            String stepId = "s" + i;
            int result = indexOf(events, stepId, TaskEventType.STEP_RESULT);
            int summary = indexOf(events, stepId, TaskEventType.STEP_SUMMARY);
            int completed = indexOf(events, stepId, TaskEventType.STEP_COMPLETED);
            assertThat(result).isGreaterThanOrEqualTo(0);
            assertThat(summary).isGreaterThan(result);
            assertThat(completed).isGreaterThan(summary);
        }
        assertThat(context.getPlan().allDone()).isTrue();
        assertThat(events.stream().filter(event -> event.type() == TaskEventType.TASK_SUMMARY)).hasSize(1);
    }

    private int indexOf(List<AgentEvent> events, String stepId, TaskEventType type) {
        for (int i = 0; i < events.size(); i++) {
            AgentEvent event = events.get(i);
            if (event.type() == type && stepId.equals(event.stepId())) return i;
        }
        return -1;
    }
}
