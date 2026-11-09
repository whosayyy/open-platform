package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.agent.store.AgentRunRecord;
import com.yin.ai.platform.core.agent.store.InMemoryAgentRunStore;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreAgentRunStoreTest {

    @Test
    void recoversAnOrphanedRunningTaskAndExposesItToAnotherRuntime() {
        InMemoryAgentRunStore runs = new InMemoryAgentRunStore();
        AgentRequest request = new AgentRequest("recover the task", "user-1", "session-1",
                Map.of("parentTaskId", "parent-1"), null, null, null,
                ExecutionContext.create("trace-1"));
        Instant createdAt = Instant.parse("2026-08-24T00:00:00Z");
        assertThat(runs.create(new AgentRunRecord("task-1", "parent-1", request,
                AgentStatus.RUNNING, "DIRECT", createdAt, createdAt,
                null, null, List.of(), false, 0L))).isTrue();

        DefaultAgentRuntime restarted = runtime(runs);
        AgentResult result = restarted.resume(new AgentResumeRequest(
                        "task-1", Map.of(), ExecutionContext.create("trace-2")), null)
                .completion().toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
        assertThat(result.output()).isEqualTo("recovered");
        assertThat(runs.load("task-1").orElseThrow().revision()).isGreaterThan(0L);

        DefaultAgentRuntime observer = runtime(runs);
        assertThat(observer.snapshot("task-1")).get()
                .extracting(AgentSnapshot::status, AgentSnapshot::parentTaskId)
                .containsExactly(AgentStatus.COMPLETED, "parent-1");
        assertThat(observer.listChildren("parent-1"))
                .extracting(AgentSnapshot::taskId)
                .containsExactly("task-1");
    }

    private static DefaultAgentRuntime runtime(InMemoryAgentRunStore runs) {
        return DefaultAgentRuntime.builder()
                .model(new FixedModelClient())
                .tools(new DefaultToolRegistry())
                .runStore(runs)
                .executor(Runnable::run)
                .build();
    }

    private static final class FixedModelClient implements ModelClient {
        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            return new ModelResponse("recovered", "stop", null, Map.of());
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
