package com.yin.ai.platform.memory.episodic;

import com.yin.ai.platform.core.memory.MemoryId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Episode — 将 Timeline 中关联的 MemoryCell 聚合为逻辑片段。
 * <p>
 * Episode 是 Episodic Memory 的高层抽象：一段连续时间窗口内、语义相关的记忆单元组。
 * 可用于对话摘要、任务回顾、Workflow step 重放等场景。
 *
 * @param id        唯一标识
 * @param memoryId  所属 Memory
 * @param title     标题（可读描述）
 * @param summary   摘要（LLM 生成或规则提取）
 * @param type      片段类型
 * @param spanFrom  片段起始时间
 * @param spanTo    片段结束时间
 * @param cellIds   包含的 MemoryCell ID 列表
 * @param metadata  扩展元数据
 */
public record Episode(
        String id,
        MemoryId memoryId,
        String title,
        String summary,
        EpisodeType type,
        Instant spanFrom,
        Instant spanTo,
        List<String> cellIds,
        Map<String, Object> metadata) {

    public Episode {
        cellIds = cellIds != null ? List.copyOf(cellIds) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static Episode of(String id, MemoryId memoryId, String title, EpisodeType type,
                              Instant spanFrom, Instant spanTo, List<String> cellIds) {
        return new Episode(id, memoryId, title, "", type, spanFrom, spanTo, cellIds, Map.of());
    }

    public Episode withSummary(String summary) {
        return new Episode(id, memoryId, title, summary, type, spanFrom, spanTo, cellIds, metadata);
    }

    public Episode withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new java.util.HashMap<>(metadata);
        newMeta.put(key, value);
        return new Episode(id, memoryId, title, summary, type, spanFrom, spanTo, cellIds, newMeta);
    }

    /** 片段内包含的 Cell 数量 */
    public int cellCount() {
        return cellIds.size();
    }
}
