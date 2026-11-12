package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpoint;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAgentCheckpointStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void keepsTheLatestPortableCheckpointAndDeletesIt() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RLock lock = mock(RLock.class);
        AtomicReference<String> stored = new AtomicReference<>();
        when(redisson.<String>getBucket(eq("test:checkpoint:v1:task-1"),
                eq(StringCodec.INSTANCE)))
                .thenReturn(bucket);
        when(redisson.getLock("test:checkpoint-lock:v1:task-1")).thenReturn(lock);
        when(bucket.get()).thenAnswer(ignored -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(bucket).set(org.mockito.ArgumentMatchers.anyString());
        RedisAgentCheckpointStore store = new RedisAgentCheckpointStore(redisson, "test:");
        AgentCheckpoint latest = checkpoint(2L, "latest");

        store.save(latest);
        store.save(checkpoint(1L, "stale"));

        assertThat(stored.get()).contains("\"schemaVersion\":1")
                .doesNotContain("@class", "AgentCheckpoint");
        assertThat(store.load("task-1")).contains(latest);
        store.delete("task-1");
        verify(bucket).delete();
        verify(lock, org.mockito.Mockito.times(2)).unlock();
    }

    private static AgentCheckpoint checkpoint(long revision, String phase) {
        return new AgentCheckpoint("task-1", "PLAN_EXECUTE", phase, revision,
                Map.of("stepIndex", 1), Instant.parse("2026-08-24T00:00:00Z"));
    }
}
