package com.yin.ai.platform.workflow.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStepIdempotencyStoreTest {

    @Test
    void guardsAndReplaysCommittedStepWithLocalFallback() {
        RedisStepIdempotencyStore store = new RedisStepIdempotencyStore(null);

        assertThat(store.begin("task", "publish")).isEqualTo(StepIdempotencyStore.BeginResult.ACQUIRED);
        assertThat(store.begin("task", "publish")).isEqualTo(StepIdempotencyStore.BeginResult.IN_PROGRESS);

        store.commit("task", "publish", "{\"success\":true}");
        assertThat(store.begin("task", "publish")).isEqualTo(StepIdempotencyStore.BeginResult.COMMITTED);
        assertThat(store.loadCommittedResult("task", "publish")).isEqualTo("{\"success\":true}");
    }

    @Test
    void failedAttemptCanBeReleasedAndRetried() {
        RedisStepIdempotencyStore store = new RedisStepIdempotencyStore(null);
        assertThat(store.begin("task", "save")).isEqualTo(StepIdempotencyStore.BeginResult.ACQUIRED);
        store.release("task", "save");
        assertThat(store.begin("task", "save")).isEqualTo(StepIdempotencyStore.BeginResult.ACQUIRED);
    }
}
