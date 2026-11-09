package com.yin.ai.platform.core.agent.store;

import com.yin.ai.platform.core.agent.AgentInstruction;
import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentStatus;
import com.yin.ai.platform.core.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunRecordTest {

    @Test
    void roundTripsThroughPortableScalarCollections() {
        Instant timestamp = Instant.parse("2026-08-24T00:00:00Z");
        ExecutionContext context = new ExecutionContext("trace-1", "conversation-1",
                "tenant-1", "user-1", "test", "execute", timestamp,
                Map.of("roles", List.of("editor"), "attempt", 2), () -> true);
        AgentRequest request = new AgentRequest("finish", "user-1", "session-1",
                Map.of("parentTaskId", "parent-1"), "be concise", 0.2, "model-1", context);
        AgentRunRecord source = new AgentRunRecord("task-1", "parent-1", request,
                AgentStatus.WAITING, "PLAN_EXECUTE", timestamp, timestamp,
                null, "approval required", List.of(new AgentInstruction(
                "approval", "continue", Map.of("risk", "write"))), true, 7L);

        AgentRunRecord restored = AgentRunRecord.fromPortableMap(source.toPortableMap());

        assertThat(restored.taskId()).isEqualTo(source.taskId());
        assertThat(restored.parentTaskId()).isEqualTo(source.parentTaskId());
        assertThat(restored.request().goal()).isEqualTo(source.request().goal());
        assertThat(restored.request().metadata()).isEqualTo(source.request().metadata());
        assertThat(restored.request().executionContext().attributes())
                .isEqualTo(source.request().executionContext().attributes());
        assertThat(restored.request().executionContext().cancellationToken()
                .isCancellationRequested()).isFalse();
        assertThat(restored.queuedInstructions()).isEqualTo(source.queuedInstructions());
        assertThat(restored.cancellationRequested()).isTrue();
        assertThat(restored.revision()).isEqualTo(7L);
    }

    @Test
    void rejectsProcessSpecificAttributeValues() {
        ExecutionContext context = ExecutionContext.create("trace-1")
                .withAttribute("notPortable", new Object());
        AgentRunRecord record = new AgentRunRecord("task-1", null,
                AgentRequest.of("goal", null, null, context), AgentStatus.CREATED,
                "DIRECT", null, null, null, null, List.of(), false, 0L);

        assertThatThrownBy(record::toPortableMap)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported agent run value");
    }
}
