package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.agent.checkpoint.InMemoryAgentCheckpointStore;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class CoreAgentClarificationTest {

    @Test
    void pausesDeepResearchAndResumesWithExactlyBoundClarificationAnswers() {
        QueueModel model = new QueueModel(
                response("""
                        {"action":"ask","questions":["Which region?"],
                         "options":{"Which region?":["China","Global"]},
                         "reason":"Region changes the research scope"}
                        """),
                response("Research AI regulation in China during 2026"),
                response("""
                        {"steps":[{"id":"research","description":"Gather evidence",
                        "executor":"llm","dependsOn":[]}]}
                        """),
                response("verified evidence"),
                response("{\"decision\":\"CONTINUE\"}"),
                response("# Research report"));
        InMemoryAgentCheckpointStore checkpoints = new InMemoryAgentCheckpointStore();
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(model)
                .tools(new DefaultToolRegistry())
                .checkpoints(checkpoints)
                .executor(Runnable::run)
                .build();
        List<String> waitingEvents = new ArrayList<>();

        AgentResult waiting = runtime.submit(request(),
                        event -> waitingEvents.add(event.type()))
                .completion().toCompletableFuture().join();

        assertThat(waiting.status()).isEqualTo(AgentStatus.WAITING);
        assertThat(waitingEvents).contains("clarification.required", "agent.waiting");
        assertThat(checkpoints.load(waiting.taskId()).orElseThrow().phase())
                .isEqualTo("waiting_clarification");
        String clarificationId = clarificationId(waiting);
        assertThat(model.requests).extracting(request -> request.metadata().get("operation"))
                .containsExactly("agent.research.clarify");

        AgentResult unanswered = runtime.resume(new AgentResumeRequest(waiting.taskId(), Map.of(),
                        ExecutionContext.create("empty-resume-trace")), AgentEventListener.NOOP)
                .completion().toCompletableFuture().join();

        assertThat(unanswered.status()).isEqualTo(AgentStatus.WAITING);
        assertThat(clarificationId(unanswered)).isEqualTo(clarificationId);
        assertThat(model.requests).hasSize(1);

        AgentResult stillWaiting = runtime.resume(new AgentResumeRequest(waiting.taskId(), Map.of(
                        "clarificationId", "another-request",
                        "Which region?", "China"), ExecutionContext.create("wrong-trace")),
                AgentEventListener.NOOP).completion().toCompletableFuture().join();

        assertThat(stillWaiting.status()).isEqualTo(AgentStatus.WAITING);
        assertThat(clarificationId(stillWaiting)).isEqualTo(clarificationId);
        assertThat(model.requests).hasSize(1);
        List<String> resumedEvents = new ArrayList<>();

        AgentResult completed = runtime.resume(new AgentResumeRequest(waiting.taskId(), Map.of(
                        "clarificationId", clarificationId,
                        "Which region?", "China"), ExecutionContext.create("resume-trace")),
                event -> resumedEvents.add(event.type()))
                .completion().toCompletableFuture().join();

        assertThat(completed.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(completed.output()).isEqualTo("# Research report");
        assertThat(completed.metadata()).containsEntry(
                "clarifiedGoal", "Research AI regulation in China during 2026");
        assertThat(checkpoints.load(waiting.taskId())).isEmpty();
        assertThat(resumedEvents).contains("clarification.resolved", "agent.completed");
        assertThat(model.requests).extracting(request -> request.metadata().get("operation"))
                .containsExactly("agent.research.clarify",
                        "agent.research.clarification.merge", "agent.plan.generate",
                        "agent.plan.step", "agent.plan.reflect",
                        "agent.research.synthesize");
        assertThat(model.requests.get(2).messages().getLast().content())
                .contains("Research AI regulation in China during 2026");
    }

    @Test
    void resumesPlanningFailureWithoutRepeatingCompletedClarification() {
        QueueModel model = new QueueModel(
                response("""
                        {"action":"clarified","goal":"Research the 2026 China AI market"}
                        """),
                new IllegalStateException("planner unavailable"),
                response("""
                        {"steps":[{"id":"research","description":"Gather evidence",
                        "executor":"llm","dependsOn":[]}]}
                        """),
                response("verified evidence"),
                response("{\"decision\":\"CONTINUE\"}"),
                response("recovered report"));
        InMemoryAgentCheckpointStore checkpoints = new InMemoryAgentCheckpointStore();
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(model)
                .tools(new DefaultToolRegistry())
                .checkpoints(checkpoints)
                .executor(Runnable::run)
                .build();

        AgentResult failed = runtime.execute(request());

        assertThat(failed.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(checkpoints.load(failed.taskId()).orElseThrow().phase())
                .isEqualTo("planning");
        assertThat(checkpoints.load(failed.taskId()).orElseThrow().state())
                .containsEntry("clarifiedGoal", "Research the 2026 China AI market");

        AgentResult completed = runtime.resume(new AgentResumeRequest(failed.taskId(), Map.of(),
                        ExecutionContext.create("resume-trace")), AgentEventListener.NOOP)
                .completion().toCompletableFuture().join();

        assertThat(completed.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(completed.output()).isEqualTo("recovered report");
        assertThat(model.requests.stream()
                .filter(request -> "agent.research.clarify".equals(
                        request.metadata().get("operation"))))
                .hasSize(1);
        assertThat(model.requests.stream()
                .filter(request -> "agent.plan.generate".equals(
                        request.metadata().get("operation"))))
                .hasSize(2);
    }

    private static AgentRequest request() {
        return new AgentRequest("Research AI regulation", "user-1", "session-1",
                Map.of("forcedMode", "DEEP_RESEARCH"), null, null, null,
                ExecutionContext.create("trace-1"));
    }

    private static String clarificationId(AgentResult result) {
        Object raw = result.metadata().get("clarification");
        assertThat(raw).isInstanceOf(Map.class);
        return String.valueOf(((Map<?, ?>) raw).get("clarificationId"));
    }

    private static ModelResponse response(String content) {
        return new ModelResponse(content, "stop", null, Map.of());
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
