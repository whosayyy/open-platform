package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.approval.ApprovalRequest;
import com.yin.ai.platform.core.agent.approval.ApprovalRisk;
import com.yin.ai.platform.core.agent.checkpoint.InMemoryAgentCheckpointStore;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CoreAgentApprovalTest {

    @Test
    void pausesPlanBeforeSensitiveStepAndExecutesItAfterMatchingApproval() {
        InMemoryAgentCheckpointStore checkpoints = new InMemoryAgentCheckpointStore();
        ApprovalModel model = new ApprovalModel();
        DefaultAgentRuntime runtime = runtime(model, checkpoints);

        AgentResult waiting = runtime.execute(request("PLAN_EXECUTE"));

        assertThat(waiting.status()).isEqualTo(AgentStatus.WAITING);
        assertThat(runtime.snapshot(waiting.taskId()).orElseThrow().status())
                .isEqualTo(AgentStatus.WAITING);
        assertThat(checkpoints.load(waiting.taskId()).orElseThrow().phase())
                .isEqualTo("waiting_approval");
        String approvalId = approvalId(waiting);
        assertThat(model.planCalls).hasValue(1);
        assertThat(model.stepCalls).hasValue(0);
        List<String> resumedEvents = new ArrayList<>();

        AgentResult stillWaiting = runtime.resume(new AgentResumeRequest(waiting.taskId(), Map.of(
                        "approvalId", "another-approval",
                        "approvalDecision", "approve"), ExecutionContext.create("wrong-trace")),
                AgentEventListener.NOOP).completion().toCompletableFuture().join();

        assertThat(stillWaiting.status()).isEqualTo(AgentStatus.WAITING);
        assertThat(approvalId(stillWaiting)).isEqualTo(approvalId);
        assertThat(model.stepCalls).hasValue(0);

        AgentResult completed = runtime.resume(new AgentResumeRequest(waiting.taskId(), Map.of(
                        "approvalId", approvalId,
                        "approvalDecision", "approve",
                        "approvalResolvedBy", "user:7"), ExecutionContext.create("resume-trace")),
                event -> resumedEvents.add(event.type()))
                .completion().toCompletableFuture().join();

        assertThat(completed.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(completed.output()).isEqualTo("approved output");
        assertThat(checkpoints.load(waiting.taskId())).isEmpty();
        assertThat(model.planCalls).hasValue(1);
        assertThat(model.stepCalls).hasValue(1);
        assertThat(resumedEvents).contains("approval.resolved", "agent.completed");
    }

    @Test
    void rejectsDeepResearchStepWithoutExecutingSensitiveAction() {
        InMemoryAgentCheckpointStore checkpoints = new InMemoryAgentCheckpointStore();
        ApprovalModel model = new ApprovalModel();
        DefaultAgentRuntime runtime = runtime(model, checkpoints);

        AgentResult waiting = runtime.execute(request("DEEP_RESEARCH"));
        String approvalId = approvalId(waiting);
        List<String> resumedEvents = new ArrayList<>();

        AgentResult completed = runtime.resume(new AgentResumeRequest(waiting.taskId(), Map.of(
                        "approvalId", approvalId,
                        "approvalDecision", "reject"), ExecutionContext.create("resume-trace")),
                event -> resumedEvents.add(event.type()))
                .completion().toCompletableFuture().join();

        assertThat(completed.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(completed.output()).isEqualTo("research report");
        assertThat(model.planCalls).hasValue(1);
        assertThat(model.stepCalls).hasValue(0);
        assertThat(model.researchSynthesisCalls).hasValue(1);
        assertThat(resumedEvents).contains("approval.resolved", "plan.step.skipped");
    }

    private static DefaultAgentRuntime runtime(ApprovalModel model,
                                               InMemoryAgentCheckpointStore checkpoints) {
        ApprovalPolicy policy = context -> Optional.of(ApprovalRequest.of(
                context.taskId(), context.step().id(), "execute sensitive step",
                "external side effect", ApprovalRisk.HIGH,
                Map.of("executor", context.step().executor())));
        return DefaultAgentRuntime.builder()
                .model(model)
                .tools(new DefaultToolRegistry())
                .checkpoints(checkpoints)
                .approvals(policy)
                .executor(Runnable::run)
                .build();
    }

    private static AgentRequest request(String mode) {
        return new AgentRequest("perform guarded work", "user-1", "session-1",
                Map.of("forcedMode", mode, "clarifiedGoal", "verified guarded work"),
                null, null, null, ExecutionContext.create("trace-1"));
    }

    private static String approvalId(AgentResult result) {
        assertThat(result.status()).isEqualTo(AgentStatus.WAITING);
        Object raw = result.metadata().get("approval");
        assertThat(raw).isInstanceOf(Map.class);
        return String.valueOf(((Map<?, ?>) raw).get("approvalId"));
    }

    private static final class ApprovalModel implements ModelClient {
        private final AtomicInteger planCalls = new AtomicInteger();
        private final AtomicInteger stepCalls = new AtomicInteger();
        private final AtomicInteger researchSynthesisCalls = new AtomicInteger();

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            String operation = String.valueOf(request.metadata().get("operation"));
            return switch (operation) {
                case "agent.plan.generate" -> {
                    planCalls.incrementAndGet();
                    yield response("""
                            {"steps":[{"id":"guarded","description":"Guarded action",
                            "executor":"llm","dependsOn":[]}]}
                            """);
                }
                case "agent.plan.step" -> {
                    stepCalls.incrementAndGet();
                    yield response("approved output");
                }
                case "agent.research.synthesize" -> {
                    researchSynthesisCalls.incrementAndGet();
                    yield response("research report");
                }
                default -> throw new AssertionError("Unexpected operation: " + operation);
            };
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
