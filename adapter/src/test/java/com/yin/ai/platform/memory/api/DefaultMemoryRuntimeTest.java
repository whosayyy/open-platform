package com.yin.ai.platform.memory.api;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemoryStats;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import com.yin.ai.platform.memory.working.WorkingMemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultMemoryRuntime")
class DefaultMemoryRuntimeTest {

    private MemoryRuntime runtime;
    private MemoryContext ctx;
    private InMemoryMemoryStoreAdapter store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStoreAdapter();
        WorkingMemoryManager manager = new DefaultWorkingMemoryManager(store);
        runtime = new DefaultMemoryRuntime(manager, store);
        ctx = MemoryContext.empty();
    }

    @Test
    @DisplayName("getOrCreateWorkingMemory creates a working memory")
    void createWorkingMemory() {
        WorkingMemory wm = runtime.getOrCreateWorkingMemory(ctx);
        assertThat(wm).isNotNull();
        assertThat(wm.getState()).isEqualTo(
                com.yin.ai.platform.core.memory.working.WorkingMemoryState.ACTIVE);
    }

    @Test
    @DisplayName("append and retrieve cells")
    void appendAndRetrieve() {
        MemoryId id = MemoryId.of("ep-1", MemoryType.EPISODIC, "user-1");
        runtime.append(id, "hello", ctx);
        runtime.append(id, "world", ctx);
        MemoryQuery query = MemoryQuery.byText("hello", 10);
        QueryResult result = runtime.retrieve(query, ctx);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).cell().content()).isEqualTo("hello");
    }

    @Test
    @DisplayName("create and find resume point")
    void resumePointLifecycle() {
        WorkingMemory wm = runtime.getOrCreateWorkingMemory(ctx);
        wm.putVariable("step", 1);
        runtime.createResumePoint(wm.getId(), "checkpoint");
        Optional<WorkingMemory> resumed = runtime.resumeFromPoint(
                wm.getId(), "checkpoint", ctx);
        assertThat(resumed).isPresent();
    }

    @Test
    @DisplayName("discard removes memory")
    void discardMemory() {
        MemoryId id = MemoryId.of("ep-2", MemoryType.EPISODIC, "user-1");
        runtime.append(id, "data", ctx);
        runtime.discard(id);
        MemoryQuery query = MemoryQuery.byText("data", 10);
        QueryResult result = runtime.retrieve(query, ctx);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("stats returns aggregate counts")
    void stats() {
        MemoryId id = MemoryId.of("ep-3", MemoryType.EPISODIC, "user-1");
        runtime.append(id, "msg1", ctx);
        runtime.append(id, "msg2", ctx);
        MemoryStats stats = runtime.stats();
        assertThat(stats.totalCells()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("fallback retrieval isolates memories by user")
    void fallbackRetrievalIsolatesByUser() {
        MemoryContext userOne = new MemoryContext(null, null, 1L, null, null,
                java.time.Instant.now(), java.util.Map.of());
        MemoryContext userTwo = new MemoryContext(null, null, 2L, null, null,
                java.time.Instant.now(), java.util.Map.of());
        runtime.append(MemoryId.of("user-one", MemoryType.SEMANTIC, "1"),
                "shared-key owner-one", userOne);
        runtime.append(MemoryId.of("user-two", MemoryType.SEMANTIC, "2"),
                "shared-key owner-two", userTwo);

        QueryResult result = runtime.retrieve(MemoryQuery.byText("shared-key", 10), userOne);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().cell().content()).isEqualTo("shared-key owner-one");
    }

    @Test
    @DisplayName("discardCell removes only the selected fact")
    void discardCellIsPrecise() {
        MemoryId id = MemoryId.of("semantic-user-1", MemoryType.SEMANTIC, "1");
        MemoryCell first = runtime.append(id, "first fact", ctx);
        MemoryCell second = runtime.append(id, "second fact", ctx);

        boolean deleted = runtime.discardCell(id, first.cellId());

        assertThat(deleted).isTrue();
        assertThat(runtime.readTimeline(id, 10))
                .extracting(MemoryCell::cellId)
                .containsExactly(second.cellId());
    }
}
