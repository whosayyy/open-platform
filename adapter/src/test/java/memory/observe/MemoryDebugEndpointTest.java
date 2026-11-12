package com.yin.ai.platform.memory.observe;

import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemoryStats;
import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.memory.cache.TieredMemoryCacheManager;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryDebugEndpoint")
class MemoryDebugEndpointTest {

    private MemoryDebugEndpoint endpoint;
    private MemoryRuntime runtime;
    private MemoryTracer tracer;

    @BeforeEach
    void setUp() {
        InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
        DefaultWorkingMemoryManager wmManager = new DefaultWorkingMemoryManager(store);
        runtime = new DefaultMemoryRuntime(wmManager, store);
        tracer = new MemoryTracer();
        TieredMemoryCacheManager cacheManager = new TieredMemoryCacheManager(
                100, Duration.ofMinutes(1), true);

        endpoint = new MemoryDebugEndpoint(runtime, List.of(tracer), cacheManager);
    }

    @Test
    @DisplayName("stats 返回全局统计")
    void shouldReturnStats() {
        MemoryStats stats = endpoint.stats();
        assertThat(stats).isNotNull();
        assertThat(stats.totalMemories()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("workingMemory 加载指定 WM")
    void shouldLoadWorkingMemory() {
        WorkingMemory wm = runtime.getOrCreateWorkingMemory(MemoryContext.empty());
        Optional<WorkingMemory> loaded = endpoint.workingMemory(wm.getId());
        assertThat(loaded).isPresent();
    }

    @Test
    @DisplayName("workingMemory 找不到返回 empty")
    void shouldReturnEmptyForUnknown() {
        MemoryId unknown = MemoryId.of("nonexistent", MemoryType.WORKING, "u");
        assertThat(endpoint.workingMemory(unknown)).isEmpty();
    }

    @Test
    @DisplayName("cacheStats 返回缓存命中率")
    void shouldReturnCacheStats() {
        Map<String, Object> stats = endpoint.cacheStats();
        assertThat(stats).containsKeys("estimatedSize", "totalHitRate", "totalRequestCount");
    }

    @Test
    @DisplayName("traceInfo 返回 Trace 汇总")
    void shouldReturnTraceInfo() {
        Map<String, Object> info = endpoint.traceInfo();
        assertThat(info).containsKeys("tracerCount", "traceIds", "totalTraceIds");
        assertThat(info.get("tracerCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("debug 返回完整 Debug 信息")
    void shouldReturnDebug() {
        Map<String, Object> debug = endpoint.debug();
        assertThat(debug).containsKeys("stats", "cache", "trace", "tracerCount");
    }
}
