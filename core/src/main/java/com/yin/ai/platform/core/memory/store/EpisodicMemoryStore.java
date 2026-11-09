package com.yin.ai.platform.core.memory.store;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

import java.util.List;

/**
 * Episodic Memory 专用存储 SPI — 管理 Timeline 追加、范围读取、CAS 写入。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>{@link #append} 使用 Redis RList RPUSH 或 Stream XADD，原子追加</li>
 *   <li>{@link #casWrite} 使用 Lua 脚本原子性检查版本号 + 更新，防止多实例写冲突</li>
 *   <li>{@link #readTimeline} 支持从指定位置读取，用于增量同步和故障恢复</li>
 * </ul>
 */
public interface EpisodicMemoryStore extends MemoryStore {

    @Override
    default boolean supports(MemoryType memoryType) {
        return memoryType == MemoryType.EPISODIC || memoryType == MemoryType.SESSION;
    }

    /** 追加一个 Cell，返回带全局序列号的 Cell */
    MemoryCell append(MemoryId id, MemoryCell cell);

    /** 批量追加 */
    List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells);

    /** 从 Timeline 读取最近 limit 条 */
    List<MemoryCell> readTimeline(MemoryId id, int limit);

    /** 读取全部 Cell */
    List<MemoryCell> readAll(MemoryId id);

    /** 获取 Cell 总数 */
    long size(MemoryId id);

    /** Refresh source retention before a potentially slow consolidation attempt. */
    default void refreshRetention(MemoryId id) {
    }

    /** 删除指定 Memory 的所有数据 */
    void deleteAll(MemoryId id);

    /**
     * Delete a single cell. The compatibility implementation rewrites the
     * bounded timeline; distributed stores should override this atomically.
     */
    default boolean deleteCell(MemoryId id, MemoryCellId cellId) {
        List<MemoryCell> existing = readAll(id);
        List<MemoryCell> retained = existing.stream()
                .filter(cell -> !cell.cellId().equals(cellId))
                .toList();
        if (retained.size() == existing.size()) {
            return false;
        }
        deleteAll(id);
        if (!retained.isEmpty()) {
            appendBatch(id, retained);
        }
        return true;
    }

    /** CAS 乐观锁更新指定 Cell */
    @Override
    boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion);
}
