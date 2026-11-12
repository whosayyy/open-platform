package com.yin.ai.platform.memory.integration;

import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EngineMemoryBridge")
class EngineMemoryBridgeTest {

    private EngineMemoryBridge bridge;
    private MemoryRuntime memoryRuntime;
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
        DefaultWorkingMemoryManager wmManager = new DefaultWorkingMemoryManager(store);
        memoryRuntime = new DefaultMemoryRuntime(wmManager, store);
        bridge = new EngineMemoryBridge(memoryRuntime);

        ctx = new ExecutionContext(
                UUID.randomUUID().toString(), "conv-1", 1001L, null, null,
                java.util.Map.of(), "test-skill", "chat",
                java.time.Instant.now(), java.util.Map.of());
    }

    @Test
    @DisplayName("beforeTask 创建 WorkingMemory")
    void shouldCreateWorkingMemory() {
        WorkingMemory wm = bridge.beforeTask(ctx);
        assertThat(wm).isNotNull();
        assertThat(wm.getId().type()).isEqualTo(MemoryType.WORKING);
    }

    @Test
    @DisplayName("afterTask 写入 Episodic Memory")
    void shouldWriteEpisodicMemory() {
        WorkingMemory wm = bridge.beforeTask(ctx);
        MemoryId episodicId = bridge.afterTask(wm, ctx, "Task completed");
        assertThat(episodicId).isNotNull();
        assertThat(memoryRuntime.readTimeline(episodicId, 10)).hasSize(1);
    }

    @Test
    @DisplayName("createResumePoint 创建恢复点")
    void shouldCreateResumePoint() {
        WorkingMemory wm = bridge.beforeTask(ctx);
        var point = bridge.createResumePoint(wm, "before_tool");
        assertThat(point).isNotNull();
        assertThat(point.label()).isEqualTo("before_tool");
    }

    @Test
    @DisplayName("onTaskFailure 创建错误恢复点")
    void shouldCreateFailureResumePoint() {
        WorkingMemory wm = bridge.beforeTask(ctx);
        var result = bridge.onTaskFailure(wm, new RuntimeException("test error"));
        assertThat(result).isPresent();
        assertThat(result.get().label()).startsWith("error_");
    }

    @Test
    @DisplayName("listResumePoints 列出恢复点")
    void shouldListResumePoints() {
        WorkingMemory wm = bridge.beforeTask(ctx);
        bridge.createResumePoint(wm, "checkpoint-1");
        bridge.createResumePoint(wm, "checkpoint-2");

        var labels = bridge.listResumePoints(wm);
        assertThat(labels).contains("checkpoint-1", "checkpoint-2");
    }

    @Test
    @DisplayName("memoryRuntime 可获取内部 Runtime")
    void shouldExposeRuntime() {
        assertThat(bridge.memoryRuntime()).isSameAs(memoryRuntime);
    }
}
