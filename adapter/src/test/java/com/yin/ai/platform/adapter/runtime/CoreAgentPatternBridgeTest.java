package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.agent.pattern.ContextSnapshot;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.approval.ApprovalRequest;
import com.yin.ai.platform.core.agent.approval.ApprovalRisk;
import com.yin.ai.platform.core.agent.clarification.ClarificationAnalysis;
import com.yin.ai.platform.core.agent.clarification.ClarificationRequest;
import com.yin.ai.platform.core.agent.clarification.ResearchClarifier;
import com.yin.ai.platform.core.agent.pattern.PatternContext;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelUsage;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CoreAgentPatternBridgeTest {

    @Test
    void delegatesPlanExecutionAndProjectsUsageAndEventsIntoLegacyContext() {
        QueueModel model = new QueueModel(
                response("""
                        {"steps":[{"id":"draft","description":"Draft answer",
                        "executor":"llm","dependsOn":[]}]}
                        """, 10, 2),
                response("core planned answer", 5, 5));
        List<AgentEvent> published = new ArrayList<>();
        AgentExecutionContext context = context(AgentMode.PLAN_EXECUTE, published);
        CoreAgentPatternBridge bridge = new CoreAgentPatternBridge(model,
                new DefaultToolRegistry(), policy(true,
                com.yin.ai.platform.core.agent.pattern.AgentMode.PLAN_EXECUTE));

        List<AgentEvent> events = bridge.execute(AgentMode.PLAN_EXECUTE, context)
                .collectList().block();

        assertThat(context.getState()).isEqualTo(TaskState.RUNNING);
        assertThat(context.getVariable("finalOutput")).isEqualTo("core planned answer");
        assertThat(context.getVariable("corePatternMode")).isEqualTo("PLAN_EXECUTE");
        assertThat(context.getVariable("usage.promptTokens")).isEqualTo(15);
        assertThat(context.getVariable("usage.completionTokens")).isEqualTo(7);
        assertThat(context.getVariable("usage.totalTokens")).isEqualTo(22);
        assertThat(events).extracting(AgentEvent::type)
                .contains(TaskEventType.TASK_PLANNING, TaskEventType.PLAN_CREATED,
                        TaskEventType.STEP_STARTED, TaskEventType.STEP_COMPLETED);
        assertThat(published).containsExactlyElementsOf(events);
        assertThat(context.snapshot().variables().values())
                .noneMatch(com.yin.ai.platform.core.agent.plan.AgentPlan.class::isInstance);
    }

    @Test
    void onlyDelegatesConfiguredFreshRequestsAndHonorsPerRequestOptOut() {
        CoreAgentPatternBridge bridge = new CoreAgentPatternBridge(new QueueModel(),
                new DefaultToolRegistry(), policy(true,
                com.yin.ai.platform.core.agent.pattern.AgentMode.DIRECT));
        AgentExecutionContext context = context(AgentMode.DIRECT, new ArrayList<>());

        assertThat(bridge.supports(AgentMode.DIRECT, context, false)).isTrue();
        assertThat(bridge.supports(AgentMode.DIRECT, context, true)).isFalse();
        assertThat(bridge.supports(AgentMode.REACT, context, false)).isFalse();
        assertThat(bridge.supports(AgentMode.MULTI_AGENT, context, false)).isFalse();

        context.putVariable("coreDelegation", "false");
        assertThat(bridge.supports(AgentMode.DIRECT, context, false)).isFalse();
    }

    @Test
    void restoresCorePlanCheckpointThroughLegacyContextWithoutReplanning() {
        QueueModel model = new QueueModel(
                response("""
                        {"steps":[
                          {"id":"s1","description":"First","executor":"llm","dependsOn":[]},
                          {"id":"s2","description":"Second","executor":"llm","dependsOn":["s1"]}
                        ]}
                        """, 1, 1),
                response("first result", 1, 1),
                new IllegalStateException("transient provider failure"),
                response("second result", 1, 1),
                response("final answer", 1, 1));
        List<AgentEvent> published = new ArrayList<>();
        AgentExecutionContext initial = context(AgentMode.PLAN_EXECUTE, published);
        CoreAgentPatternBridge bridge = new CoreAgentPatternBridge(model,
                new DefaultToolRegistry(), policy(true,
                com.yin.ai.platform.core.agent.pattern.AgentMode.PLAN_EXECUTE));

        assertThatThrownBy(() -> bridge.execute(AgentMode.PLAN_EXECUTE, initial)
                .collectList().block()).hasMessage("transient provider failure");
        assertThat(initial.getVariable(LegacyContextAgentCheckpointStore.CONTEXT_KEY))
                .isInstanceOf(Map.class);
        initial.transitionTo(TaskState.FAILED);
        ContextSnapshot snapshot = initial.snapshot();
        AgentExecutionContext restored = AgentExecutionContext.restore(snapshot, null,
                publisher(published), mock(ContextStore.class));
        restored.putVariable("resume", Boolean.TRUE);

        assertThat(bridge.supports(AgentMode.PLAN_EXECUTE, restored, true)).isTrue();
        bridge.execute(AgentMode.PLAN_EXECUTE, restored).collectList().block();

        assertThat(restored.getState()).isEqualTo(TaskState.RUNNING);
        assertThat(restored.getVariable("finalOutput")).isEqualTo("final answer");
        assertThat(restored.hasVariable(LegacyContextAgentCheckpointStore.CONTEXT_KEY)).isFalse();
        assertThat(model.requests.stream()
                .filter(request -> "agent.plan.generate".equals(
                        request.metadata().get("operation")))).hasSize(1);
    }

    @Test
    void projectsCoreApprovalPauseAndResumesAfterDecision() {
        QueueModel model = new QueueModel(
                response("""
                        {"steps":[{"id":"guarded","description":"Guarded action",
                        "executor":"llm","dependsOn":[]}]}
                        """, 1, 1),
                response("approved result", 1, 1));
        List<AgentEvent> published = new ArrayList<>();
        AgentExecutionContext context = context(AgentMode.PLAN_EXECUTE, published);
        ApprovalPolicy approvals = approvalContext -> Optional.of(ApprovalRequest.of(
                approvalContext.taskId(), approvalContext.step().id(), "guarded action",
                "external side effect", ApprovalRisk.HIGH, Map.of()));
        CoreAgentPatternBridge bridge = new CoreAgentPatternBridge(model,
                new DefaultToolRegistry(), policy(true,
                com.yin.ai.platform.core.agent.pattern.AgentMode.PLAN_EXECUTE), approvals);

        List<AgentEvent> waitingEvents = bridge.execute(AgentMode.PLAN_EXECUTE, context)
                .collectList().block();

        assertThat(context.getState()).isEqualTo(TaskState.WAITING);
        assertThat(waitingEvents).extracting(AgentEvent::type)
                .contains(TaskEventType.APPROVAL_REQUIRED, TaskEventType.TASK_WAITING)
                .doesNotContain(TaskEventType.STEP_STARTED);
        AgentEvent required = waitingEvents.stream()
                .filter(event -> event.type() == TaskEventType.APPROVAL_REQUIRED)
                .findFirst().orElseThrow();
        context.putVariable("resume", Boolean.TRUE);
        context.putVariable("approvalId", String.valueOf(required.payload().get("approvalId")));
        context.putVariable("approvalDecision", "approve");
        context.putVariable("approvalResolvedBy", "user:7");

        List<AgentEvent> resumedEvents = bridge.execute(AgentMode.PLAN_EXECUTE, context)
                .collectList().block();

        assertThat(context.getState()).isEqualTo(TaskState.RUNNING);
        assertThat(context.getVariable("finalOutput")).isEqualTo("approved result");
        assertThat(context.hasVariable(LegacyContextAgentCheckpointStore.CONTEXT_KEY)).isFalse();
        assertThat(resumedEvents).extracting(AgentEvent::type)
                .contains(TaskEventType.APPROVAL_RESOLVED, TaskEventType.STEP_STARTED,
                        TaskEventType.STEP_COMPLETED);
        assertThat(model.requests.stream()
                .filter(request -> "agent.plan.generate".equals(
                        request.metadata().get("operation")))).hasSize(1);
    }

    @Test
    void projectsCoreClarificationQuestionsAndResumesWithLegacyAnswerKeys() {
        QueueModel model = new QueueModel(
                response("""
                        {"steps":[{"id":"research","description":"Gather evidence",
                        "executor":"llm","dependsOn":[]}]}
                        """, 1, 1),
                response("verified evidence", 1, 1),
                response("{\"decision\":\"CONTINUE\"}", 1, 1),
                response("research report", 1, 1));
        ResearchClarifier clarifier = new ResearchClarifier() {
            @Override
            public ClarificationAnalysis analyze(String goal, PatternContext context) {
                return ClarificationAnalysis.waiting(List.of("Which region?"),
                        Map.of("Which region?", List.of("China", "Global")),
                        "Region changes scope");
            }

            @Override
            public String merge(ClarificationRequest request, Map<String, String> answers,
                                PatternContext context) {
                assertThat(answers).containsEntry("Which region?", "China");
                return "Research the China market";
            }
        };
        List<AgentEvent> published = new ArrayList<>();
        AgentExecutionContext context = context(AgentMode.DEEP_RESEARCH, published);
        CoreAgentPatternBridge bridge = new CoreAgentPatternBridge(model,
                new DefaultToolRegistry(), policy(true,
                com.yin.ai.platform.core.agent.pattern.AgentMode.DEEP_RESEARCH),
                ApprovalPolicy.NONE, clarifier);

        List<AgentEvent> waitingEvents = bridge.execute(AgentMode.DEEP_RESEARCH, context)
                .collectList().block();

        assertThat(context.getState()).isEqualTo(TaskState.WAITING);
        AgentEvent waiting = waitingEvents.stream()
                .filter(event -> event.type() == TaskEventType.TASK_WAITING)
                .findFirst().orElseThrow();
        assertThat(waiting.payload())
                .containsEntry("phase", "clarification")
                .containsEntry("questions", List.of("Which region?"));
        assertThat(waiting.payload().get("options")).isEqualTo(
                Map.of("Which region?", List.of("China", "Global")));
        assertThat(waitingEvents).extracting(AgentEvent::type)
                .doesNotContain(TaskEventType.STEP_STARTED);
        context.putVariable("resume", Boolean.TRUE);
        context.putVariable("clarificationId", waiting.payload().get("clarificationId"));
        context.putVariable("Which region?", "China");

        List<AgentEvent> resumedEvents = bridge.execute(AgentMode.DEEP_RESEARCH, context)
                .collectList().block();

        assertThat(context.getState()).isEqualTo(TaskState.RUNNING);
        assertThat(context.getVariable("finalOutput")).isEqualTo("research report");
        assertThat(context.hasVariable(LegacyContextAgentCheckpointStore.CONTEXT_KEY)).isFalse();
        assertThat(resumedEvents).extracting(AgentEvent::type)
                .contains(TaskEventType.CONTEXT_UPDATED, TaskEventType.PLAN_CREATED,
                        TaskEventType.STEP_COMPLETED);
        assertThat(model.requests.stream()
                .filter(request -> "agent.plan.generate".equals(
                        request.metadata().get("operation")))).hasSize(1);
    }

    private static AgentExecutionContext context(AgentMode mode, List<AgentEvent> published) {
        AgentExecutionContext context = new AgentExecutionContext("task-core", "session-1",
                "user-1", mode, null, publisher(published), mock(ContextStore.class));
        context.putVariable("goal", "draft the answer");
        return context;
    }

    private static EventPublisher publisher(List<AgentEvent> published) {
        return new EventPublisher() {
            @Override public void publish(AgentEvent event) { published.add(event); }
            @Override public Flux<AgentEvent> replay(String taskId, String lastEventId) {
                return Flux.empty();
            }
        };
    }

    private static CorePatternDelegationPolicy policy(
            boolean enabled, com.yin.ai.platform.core.agent.pattern.AgentMode mode) {
        return new CorePatternDelegationPolicy(enabled, java.util.Set.of(mode),
                "coreDelegation", 8, 32, 2);
    }

    private static ModelResponse response(String content, long input, long output) {
        return new ModelResponse(content, "stop",
                new ModelUsage(input, output, input + output), Map.of());
    }

    private static final class QueueModel implements ModelClient {
        private final Queue<Object> responses = new ArrayDeque<>();
        private final List<ModelRequest> requests = new ArrayList<>();

        private QueueModel(Object... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            requests.add(request);
            Object response = responses.poll();
            if (response == null) throw new AssertionError("Unexpected model call");
            if (response instanceof RuntimeException exception) throw exception;
            return (ModelResponse) response;
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
