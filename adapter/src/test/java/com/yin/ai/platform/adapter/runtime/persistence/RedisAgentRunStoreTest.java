package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentStatus;
import com.yin.ai.platform.core.agent.store.AgentRunRecord;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAgentRunStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void persistsRevisionsAndParentIndexAsPortableJson() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RLock lock = mock(RLock.class);
        RSet<String> children = mock(RSet.class);
        AtomicReference<String> stored = new AtomicReference<>();
        when(redisson.<String>getBucket(eq("test:run:v1:task-1"), eq(StringCodec.INSTANCE)))
                .thenReturn(bucket);
        when(redisson.getLock("test:run-lock:v1:task-1")).thenReturn(lock);
        when(redisson.<String>getSet(eq("test:children:v1:parent-1"), eq(StringCodec.INSTANCE)))
                .thenReturn(children);
        when(bucket.get()).thenAnswer(ignored -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(bucket).set(org.mockito.ArgumentMatchers.anyString());
        when(children.readAll()).thenReturn(Set.of("task-1"));
        RedisAgentRunStore store = new RedisAgentRunStore(redisson, "test:");
        AgentRunRecord created = record(AgentStatus.CREATED, 0L);

        assertThat(store.create(created)).isTrue();
        assertThat(store.create(created)).isFalse();
        assertThat(stored.get()).contains("\"schemaVersion\":1")
                .doesNotContain("@class", "CancellationToken");

        AgentRunRecord completed = record(AgentStatus.COMPLETED, 1L);
        assertThat(store.update(completed)).isTrue();
        assertThat(store.update(record(AgentStatus.FAILED, 3L))).isFalse();
        assertThat(store.load("task-1")).contains(completed);
        assertThat(store.listByParent("parent-1"))
                .extracting(AgentRunRecord::taskId)
                .containsExactly("task-1");
        verify(children).add("task-1");
        verify(lock, org.mockito.Mockito.atLeastOnce()).unlock();
    }

    private static AgentRunRecord record(AgentStatus status, long revision) {
        Instant timestamp = Instant.parse("2026-08-24T00:00:00Z");
        return new AgentRunRecord("task-1", "parent-1",
                AgentRequest.of("goal", "user", "session", null), status,
                "DIRECT", timestamp, timestamp,
                status == AgentStatus.COMPLETED ? "done" : null, null,
                List.of(), false, revision);
    }
}
