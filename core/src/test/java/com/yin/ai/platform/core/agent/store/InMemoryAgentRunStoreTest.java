package com.yin.ai.platform.core.agent.store;

import com.yin.ai.platform.core.agent.AgentInstruction;
import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentRunStoreTest {

    @Test
    void createsAndAdvancesOnlyTheNextRevision() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunRecord created = record("task-1", null, AgentStatus.CREATED, 0L);

        assertThat(store.create(created)).isTrue();
        assertThat(store.create(created)).isFalse();

        AgentRunRecord completed = record("task-1", null, AgentStatus.COMPLETED, 1L);
        assertThat(store.update(completed)).isTrue();
        assertThat(store.update(completed)).isFalse();
        assertThat(store.update(record("task-1", null, AgentStatus.FAILED, 3L))).isFalse();
        assertThat(store.update(record("task-1", "different-parent",
                AgentStatus.FAILED, 2L))).isFalse();
        assertThat(store.load("task-1")).contains(completed);
    }

    @Test
    void listsOnlyDirectChildrenInStableOrder() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        assertThat(store.create(record("child-b", "parent-1", AgentStatus.CREATED, 0L)))
                .isTrue();
        assertThat(store.create(record("child-a", "parent-1", AgentStatus.CREATED, 0L)))
                .isTrue();
        assertThat(store.create(record("other", "parent-2", AgentStatus.CREATED, 0L)))
                .isTrue();

        assertThat(store.listByParent("parent-1"))
                .extracting(AgentRunRecord::taskId)
                .containsExactly("child-a", "child-b");
    }

    private static AgentRunRecord record(String taskId, String parentTaskId,
                                         AgentStatus status, long revision) {
        Instant timestamp = Instant.parse("2026-08-24T00:00:00Z");
        return new AgentRunRecord(taskId, parentTaskId,
                AgentRequest.of("goal", "user", "session", null), status,
                "DIRECT", timestamp, timestamp,
                status == AgentStatus.COMPLETED ? "done" : null, null,
                List.of(new AgentInstruction("note", "continue", Map.of())),
                false, revision);
    }
}
