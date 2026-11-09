package com.yin.ai.platform.core.memory.api;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Memory Runtime 统一入口 — Agent/Workflow/Skill 访问 Memory 的唯一门面。
 * <p>
 * 设计理由：不暴露 WorkingMemoryStore、EpisodicMemoryStore 等内部 SPI，
 * 所有 Memory 操作通过此接口完成。遵循门面模式，内部组合 WorkingMemoryManager
 * 和 MemoryStore 完成实际工作。
 * <p>
 * 实现 {@link AutoCloseable} 用于优雅关闭时清理资源（如 close Episodic store）。
 */
public interface MemoryRuntime extends AutoCloseable {

    WorkingMemory getOrCreateWorkingMemory(MemoryContext ctx);

    Optional<WorkingMemory> loadWorkingMemory(MemoryId id);

    void saveWorkingMemory(WorkingMemory wm);

    ResumePoint createResumePoint(MemoryId id, String label);

    Optional<WorkingMemory> resumeFromPoint(MemoryId id, String resumePointLabel, MemoryContext ctx);

    MemoryCell append(MemoryId id, String content, MemoryContext ctx);

    /**
     * 带结构化元数据的追加（会话记忆等场景需要 attributes 承载 sessionId/roundNo）。
     * 默认实现委托给 {@link #append(MemoryId, String, MemoryContext)}——丢弃 attributes；
     * 需要保留 attributes 的实现（如 DefaultMemoryRuntime）应覆盖此方法。
     */
    default MemoryCell append(MemoryId id, String content, Map<String, Object> attributes,
                              MemoryContext ctx) {
        return append(id, content, ctx);
    }

    List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells, MemoryContext ctx);

    QueryResult retrieve(MemoryQuery query, MemoryContext ctx);

    List<MemoryCell> readTimeline(MemoryId id, int limit);

    void discard(MemoryId id);

    /**
     * Delete one memory cell without deleting the complete owner timeline.
     *
     * @return {@code true} when the cell existed and was removed
     */
    boolean discardCell(MemoryId id, MemoryCellId cellId);

    /**
     * 更新单个 Cell 的内容（CAS 乐观锁）。Cell 不存在时返回 {@code false}。
     * 默认实现不提供该能力，由具体 Runtime 覆盖。
     *
     * @return {@code true} when the cell existed and was updated
     */
    default boolean updateCell(MemoryId id, MemoryCellId cellId, String content,
                               MemoryContext ctx) {
        return false;
    }

    MemorySnapshot snapshot(MemoryId id);

    MemoryStats stats();

    /**
     * 返回当前 Runtime 已知的全部 MemoryId（用于维护调度器遍历，如 Consolidation/Eviction）。
     * 默认返回空列表；实现类应暴露内部 registry。
     */
    default List<MemoryId> allMemoryIds() {
        return List.of();
    }

    @Override
    void close();
}
