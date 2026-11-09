package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.agent.checkpoint.InMemoryAgentCheckpointStore;
import com.yin.ai.platform.core.agent.store.InMemoryAgentRunStore;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CoreAgentCheckpointResumeTest {

    @Test
    void resumesPlanFromLastCompletedStepWithoutRegeneratingIt() {
        InMemoryAgentCheckpointStore checkpoints = new InMemoryAgentCheckpointStore();
        InMemoryAgentRunStore runs = new InMemoryAgentRunStore();
        FlakyPlanModel model = new FlakyPlanModel();
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(model)
                .tools(new DefaultToolRegistry())
                .checkpoints(checkpoints)
                .runStore(runs)
                .executor(Runnable::run)
                .build();
        AgentRequest request = new AgentRequest("finish the plan", "user-1", "session-1",
                Map.of("forcedMode", "PLAN_EXECUTE"), null, null, null,
                ExecutionContext.create("trace-1"));

        AgentResult failed = runtime.execute(request);

        assertThat(failed.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(checkpoints.load(failed.taskId())).isPresent();
        assertThat(checkpoints.load(failed.taskId()).orElseThrow().toPortableMap().toString())
                .doesNotContain("AgentPlan", "PlanStep", "com.yin");
        List<String> resumedEvents = new ArrayList<>();
        DefaultAgentRuntime restarted = DefaultAgentRuntime.builder()
                .model(model)
                .tools(new DefaultToolRegistry())
                .checkpoints(checkpoints)
                .runStore(runs)
                .executor(Runnable::run)
                .build();

        AgentResult resumed = restarted.resume(new AgentResumeRequest(
                        failed.taskId(), Map.of(), ExecutionContext.create("trace-2")),
                event -> resumedEvents.add(event.type()))
                .completion().toCompletableFuture().join();

        assertThat(resumed.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(resumed.output()).isEqualTo("final answer");
        assertThat(checkpoints.load(failed.taskId())).isEmpty();
        assertThat(resumedEvents).contains("plan.resumed");
        assertThat(model.planCalls).hasValue(1);
        assertThat(model.firstStepCalls).hasValue(1);
        assertThat(model.secondStepCalls).hasValue(2);
        assertThat(model.synthesisCalls).hasValue(1);
        assertThat(model.reflectionCalls).hasValue(0);
    }

    @Test
    void resumesDeepResearchWithoutRepeatingCompletedEvidenceStep() {
        InMemoryAgentCheckpointStore checkpoints = new InMemoryAgentCheckpointStore();
        InMemoryAgentRunStore runs = new InMemoryAgentRunStore();
        FlakyPlanModel model = new FlakyPlanModel();
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(model)
                .tools(new DefaultToolRegistry())
                .checkpoints(checkpoints)
                .runStore(runs)
                .executor(Runnable::run)
                .build();
        AgentRequest request = new AgentRequest("research the topic", "user-1", "session-1",
                Map.of("forcedMode", "DEEP_RESEARCH", "clarifiedGoal", "verified topic"),
                null, null, null, ExecutionContext.create("trace-1"));

        AgentResult failed = runtime.execute(request);

        assertThat(failed.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(checkpoints.load(failed.taskId()).orElseThrow().state())
                .containsEntry("clarifiedGoal", "verified topic");
        List<String> resumedEvents = new ArrayList<>();
        DefaultAgentRuntime restarted = DefaultAgentRuntime.builder()
                .model(model)
                .tools(new DefaultToolRegistry())
                .checkpoints(checkpoints)
                .runStore(runs)
                .executor(Runnable::run)
                .build();

        AgentResult resumed = restarted.resume(new AgentResumeRequest(
                        failed.taskId(), Map.of(), ExecutionContext.create("trace-2")),
                event -> resumedEvents.add(event.type()))
                .completion().toCompletableFuture().join();

        assertThat(resumed.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(resumed.output()).isEqualTo("final answer");
        assertThat(checkpoints.load(failed.taskId())).isEmpty();
        assertThat(resumedEvents).contains("research.resumed");
        assertThat(model.planCalls).hasValue(1);
        assertThat(model.firstStepCalls).hasValue(1);
        assertThat(model.secondStepCalls).hasValue(2);
        assertThat(model.synthesisCalls).hasValue(1);
        assertThat(model.reflectionCalls).hasValue(2);
    }

    private static final class FlakyPlanModel implements ModelClient {
        private final AtomicInteger planCalls = new AtomicInteger();
        private final AtomicInteger firstStepCalls = new AtomicInteger();
        private final AtomicInteger secondStepCalls = new AtomicInteger();
        private final AtomicInteger synthesisCalls = new AtomicInteger();
        private final AtomicInteger reflectionCalls = new AtomicInteger();

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            String operation = String.valueOf(request.metadata().get("operation"));
            if ("agent.plan.generate".equals(operation)) {
                planCalls.incrementAndGet();
                return response("""
                        {"steps":[
                          {"id":"s1","description":"First","executor":"llm","dependsOn":[]},
                          {"id":"s2","description":"Second","executor":"llm","dependsOn":["s1"]}
                        ]}
                        """);
            }
            if ("agent.plan.step".equals(operation)) {
                String stepId = String.valueOf(request.metadata().get("stepId"));
                if ("s1".equals(stepId)) {
                    firstStepCalls.incrementAndGet();
                    return response("first result");
                }
                if (secondStepCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient provider failure");
                }
                return response("second result");
            }
            if ("agent.plan.synthesize".equals(operation)
                    || "agent.research.synthesize".equals(operation)) {
                synthesisCalls.incrementAndGet();
                return response("final answer");
            }
            if ("agent.plan.reflect".equals(operation)) {
                reflectionCalls.incrementAndGet();
                return response("{\"decision\":\"CONTINUE\"}");
            }
            throw new AssertionError("Unexpected operation: " + operation);
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new UnsupportedOperationException();
        }

        private ModelResponse response(String content) {
            return new ModelResponse(content, "stop", null, Map.of());
        }
    }
}
