package com.yin.ai.platform.memory.observe;

import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemorySnapshot;
import com.yin.ai.platform.core.memory.api.MemoryStats;
import com.yin.ai.platform.memory.cache.TieredMemoryCacheManager;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.event.MemoryEventBus;
import com.yin.ai.platform.core.memory.working.WorkingMemory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Memory 调试端点 — 提供 Runtime 状态的可观测接口。
 * <p>
 * 暴露以下信息：
 * <ul>
 *   <li>Memory Runtime 全局统计（Stats）</li>
 *   <li>指定 Memory 的快照（Snapshot）</li>
 *   <li>L1 缓存命中率（CacheStats）</li>
 *   <li>EventBus 状态</li>
 *   <li>Store 健康检查</li>
 * </ul>
 * <p>
 * 本类不依赖 Spring MVC，可作为 Bean 注册后在 Controller 中调用，
 * 也可以直接通过程序化方式查询。
 */
public class MemoryDebugEndpoint {

    private final MemoryRuntime memoryRuntime;
    private final List<MemoryTracer> tracers;
    private final TieredMemoryCacheManager cacheManager;

    public MemoryDebugEndpoint(MemoryRuntime memoryRuntime,
                                List<MemoryTracer> tracers,
                                TieredMemoryCacheManager cacheManager) {
        this.memoryRuntime = memoryRuntime;
        this.tracers = tracers != null ? List.copyOf(tracers) : List.of();
        this.cacheManager = cacheManager;
    }

    /** 获取全局 Memory 统计。 */
    public MemoryStats stats() {
        return memoryRuntime.stats();
    }

    /** 获取指定 Memory 的快照。 */
    public MemorySnapshot snapshot(MemoryId id) {
        return memoryRuntime.snapshot(id);
    }

    /** 加载指定 Working Memory。 */
    public Optional<WorkingMemory> workingMemory(MemoryId id) {
        return memoryRuntime.loadWorkingMemory(id);
    }

    /** 获取 L1 缓存命中率。 */
    public Map<String, Object> cacheStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (cacheManager != null) {
            TieredMemoryCacheManager.AggregateStats stats = cacheManager.aggregateStats();
            result.put("estimatedSize", cacheManager.estimatedSize());
            result.put("totalHitRate", String.format("%.2f%%", stats.totalHitRate() * 100));
            result.put("totalRequestCount", stats.totalRequestCount());
            result.put("cellListHitRate",
                    String.format("%.2f%%", stats.cellListStats().hitRate() * 100));
            result.put("cellHitRate",
                    String.format("%.2f%%", stats.cellStats().hitRate() * 100));
        }
        return result;
    }

    /** 获取 Trace 信息。 */
    public Map<String, Object> traceInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tracerCount", tracers.size());
        List<String> traceIds = tracers.stream()
                .flatMap(t -> t.getTraceIds().stream())
                .distinct()
                .toList();
        result.put("traceIds", traceIds);
        result.put("totalTraceIds", traceIds.size());
        return result;
    }

    /** 获取指定 Trace 的详细记录。 */
    public List<MemoryTracer.TraceRecord> traceDetail(String traceId) {
        return tracers.stream()
                .flatMap(t -> t.getTrace(traceId).stream())
                .toList();
    }

    /** 完整的 Debug 信息汇总。 */
    public Map<String, Object> debug() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats());
        result.put("cache", cacheStats());
        result.put("trace", traceInfo());
        result.put("tracerCount", tracers.size());
        return result;
    }
}
