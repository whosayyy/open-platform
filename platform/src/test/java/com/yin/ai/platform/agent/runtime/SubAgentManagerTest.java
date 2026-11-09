package com.yin.ai.platform.agent.runtime;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.StepTrace;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.gateway.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SubAgentManager")
class SubAgentManagerTest {

    private ExecutionContext execCtx;
    private AgentContext parentCtx;
    private Function<AgentContext, AgentResult> mockExecutor;

    @BeforeEach
    void setUp() {
        execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
        parentCtx = new AgentContext(execCtx, AgentTask.of("parent goal"), 0);
        mockExecutor = ctx -> AgentResult.ok("sub-result", List.of(), 1);
    }

    @Test
    @DisplayName("delegate returns SubAgentResult with correct agentId and result")
    void delegateSuccess() {
        AgentTask subTask = AgentTask.of("search AI chip market");
        SubAgentManager manager = new SubAgentManager(mockExecutor);

        SubAgentResult result = manager.delegate(parentCtx, subTask);

        assertThat(result.agentId()).isNotBlank();
        assertThat(result.result()).isEqualTo("sub-result");
        assertThat(result.stepCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("delegate with custom toolFilter sets filter in child context")
    void delegateWithToolFilter() {
        Set<String> filter = Set.of("searchNews", "getDetail");
        AgentConfig config = AgentConfig.defaults();
        AgentTask subTask = new AgentTask("search", Map.of(), filter, config);

        AtomicBoolean filterVerified = new AtomicBoolean(false);
        Function<AgentContext, AgentResult> verifyingExecutor = ctx -> {
            Set<String> actualFilter = ctx.getVariable("toolFilter", Set.class);
            assertThat(actualFilter).containsAll(filter);
            filterVerified.set(true);
            return AgentResult.ok("done", List.of(), 1);
        };

        SubAgentManager manager = new SubAgentManager(verifyingExecutor);
        manager.delegate(parentCtx, subTask);

        assertThat(filterVerified).isTrue();
    }

    @Test
    @DisplayName("delegate inherits parent toolFilter when task has none")
    void delegateInheritsToolFilter() {
        parentCtx.putVariable("toolFilter", Set.of("inheritedTool"));
        AgentTask subTask = AgentTask.of("sub goal");

        AtomicBoolean filterInherited = new AtomicBoolean(false);
        Function<AgentContext, AgentResult> verifyingExecutor = ctx -> {
            Set<String> actualFilter = ctx.getVariable("toolFilter", Set.class);
            assertThat(actualFilter).containsExactly("inheritedTool");
            filterInherited.set(true);
            return AgentResult.ok("done", List.of(), 1);
        };

        SubAgentManager manager = new SubAgentManager(verifyingExecutor);
        manager.delegate(parentCtx, subTask);

        assertThat(filterInherited).isTrue();
    }

    @Test
    @DisplayName("delegate throws DepthLimitExceededException when depth exceeds max")
    void depthLimitExceeded() {
        AgentConfig limitedConfig = new AgentConfig(15, Duration.ofMinutes(10), 3, false, 0,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentTask limitedTask = AgentTask.of("deep task", limitedConfig);
        AgentContext deepParent = new AgentContext(execCtx, limitedTask, 0);
        AgentTask subTask = AgentTask.of("even deeper");

        SubAgentManager manager = new SubAgentManager(mockExecutor);

        assertThatThrownBy(() -> manager.delegate(deepParent, subTask))
                .isInstanceOf(DepthLimitExceededException.class)
                .satisfies(e -> {
                    DepthLimitExceededException de = (DepthLimitExceededException) e;
                    assertThat(de.parentDepth()).isEqualTo(0);
                    assertThat(de.attemptedDepth()).isEqualTo(1);
                    assertThat(de.maxDepth()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("delegate at exact max depth succeeds")
    void delegateAtMaxDepth() {
        AgentConfig exactConfig = new AgentConfig(15, Duration.ofMinutes(10), 3, false, 1,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentTask exactTask = AgentTask.of("exact depth task", exactConfig);
        AgentContext exactParent = new AgentContext(execCtx, exactTask, 0);
        AgentTask subTask = AgentTask.of("at limit");

        SubAgentManager manager = new SubAgentManager(mockExecutor);
        SubAgentResult result = manager.delegate(exactParent, subTask);

        assertThat(result.agentId()).isNotBlank();
    }

    @Test
    @DisplayName("delegate catches executor exception and returns empty SubAgentResult")
    void executorExceptionReturnsEmpty() {
        Function<AgentContext, AgentResult> failingExecutor = ctx -> {
            throw new RuntimeException("executor failure");
        };

        SubAgentManager manager = new SubAgentManager(failingExecutor);
        SubAgentResult result = manager.delegate(parentCtx, AgentTask.of("will fail"));

        assertThat(result.agentId()).isNotBlank();
        assertThat(result.result()).isEmpty();
        assertThat(result.stepCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("child AgentContext has independent variables")
    void childContextIsolation() {
        parentCtx.putVariable("parentKey", "parentValue");

        AtomicBoolean isolated = new AtomicBoolean(false);
        Function<AgentContext, AgentResult> verifyingExecutor = ctx -> {
            assertThat(ctx.getAgentId()).isNotEqualTo(parentCtx.getAgentId());
            assertThat(ctx.getDepth()).isEqualTo(1);
            assertThat(ctx.getVariable("parentKey")).isNull();
            ctx.putVariable("childKey", "childValue");
            isolated.set(true);
            return AgentResult.ok("done", List.of(), 1);
        };

        SubAgentManager manager = new SubAgentManager(verifyingExecutor);
        manager.delegate(parentCtx, AgentTask.of("isolated"));

        assertThat(isolated).isTrue();
        assertThat(parentCtx.getVariable("childKey")).isNull();
    }

    @Test
    @DisplayName("delegate tracks parent-child relationships during execution")
    void parentChildTracking() {
        AtomicBoolean tracked = new AtomicBoolean(false);
        AtomicReference<SubAgentManager> ref = new AtomicReference<>();
        SubAgentManager manager = new SubAgentManager(ctx -> {
            SubAgentManager m = ref.get();
            List<String> childIds = m.getChildIds(parentCtx.getAgentId());
            if (!childIds.isEmpty()
                    && parentCtx.getAgentId().equals(m.getParentId(childIds.get(0)))) {
                tracked.set(true);
            }
            return AgentResult.ok("result", List.of(), 1);
        });
        ref.set(manager);

        manager.delegate(parentCtx, AgentTask.of("child"));

        assertThat(tracked).isTrue();
    }

    @Test
    @DisplayName("child is cleaned up after delegate completes")
    void cleanupAfterDelegate() {
        SubAgentManager manager = new SubAgentManager(mockExecutor);

        String childId = manager.delegate(parentCtx, AgentTask.of("child")).agentId();

        assertThat(manager.getChildIds(parentCtx.getAgentId())).doesNotContain(childId);
        assertThat(manager.getParentId(childId)).isNull();
        assertThat(manager.activeChildCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("recursive delegation (depth=2) works correctly")
    void recursiveDelegation() {
        AtomicReference<SubAgentManager> ref = new AtomicReference<>();
        SubAgentManager manager = new SubAgentManager(ctx -> {
            if (ctx.getDepth() <= 1) {
                AgentConfig childConfig = new AgentConfig(15, Duration.ofMinutes(10),
                        3, false, 3, com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
                AgentTask grandChild = new AgentTask("grandchild", Map.of(), null, childConfig);
                SubAgentResult grandResult = ref.get().delegate(ctx, grandChild);
                return AgentResult.ok(grandResult.result(), List.of(), 2);
            }
            return AgentResult.ok("leaf result", List.of(), 1);
        });
        ref.set(manager);

        SubAgentResult result = manager.delegate(parentCtx, AgentTask.of("recursive"));

        assertThat(result.result()).isEqualTo("leaf result");
    }

    @Test
    @DisplayName("delegate preserves StepTrace from executor")
    void preservesStepTrace() {
        StepTrace trace = new StepTrace(1, "search", "found", "good", "CONTINUE", 0, 123L);
        TokenUsage tokens = new TokenUsage(100, 50, 150);
        Function<AgentContext, AgentResult> traceExecutor = ctx ->
                AgentResult.ok("result", List.of(trace), tokens, 0.01, 1);

        SubAgentManager manager = new SubAgentManager(traceExecutor);
        SubAgentResult result = manager.delegate(parentCtx, AgentTask.of("tracing"));

        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).action()).isEqualTo("search");
        assertThat(result.tokenUsage().getTotalTokens()).isEqualTo(150);
        assertThat(result.cost()).isEqualTo(0.01);
    }

    @Nested
    @DisplayName("SubAgentResult")
    class SubAgentResultTest {

        @Test
        @DisplayName("of extracts all fields from AgentResult")
        void fromAgentResult() {
            StepTrace trace = StepTrace.of(1, "act", "res", 100L);
            TokenUsage tokens = new TokenUsage(10, 20, 30);
            AgentResult ar = AgentResult.ok("answer", List.of(trace), tokens, 0.05, 3);

            SubAgentResult sr = SubAgentResult.of("agent-1", ar);

            assertThat(sr.agentId()).isEqualTo("agent-1");
            assertThat(sr.result()).isEqualTo("answer");
            assertThat(sr.trace()).hasSize(1);
            assertThat(sr.tokenUsage().getTotalTokens()).isEqualTo(30);
            assertThat(sr.cost()).isEqualTo(0.05);
            assertThat(sr.stepCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("empty creates result with zero/default values")
        void emptyResult() {
            SubAgentResult sr = SubAgentResult.empty("agent-x");

            assertThat(sr.agentId()).isEqualTo("agent-x");
            assertThat(sr.result()).isEmpty();
            assertThat(sr.trace()).isEmpty();
            assertThat(sr.stepCount()).isEqualTo(0);
            assertThat(sr.cost()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("DepthLimitExceededException")
    class DepthLimitExceededExceptionTest {

        @Test
        @DisplayName("contains all depth info in message and accessors")
        void depthExceptionInfo() {
            DepthLimitExceededException ex = new DepthLimitExceededException(2, 3, 3);

            assertThat(ex.parentDepth()).isEqualTo(2);
            assertThat(ex.attemptedDepth()).isEqualTo(3);
            assertThat(ex.maxDepth()).isEqualTo(3);
            assertThat(ex.getMessage()).contains("parent=2");
            assertThat(ex.getMessage()).contains("attempted=3");
            assertThat(ex.getMessage()).contains("max=3");
        }
    }
}
