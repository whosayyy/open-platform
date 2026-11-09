package com.yin.ai.platform.core.memory.store;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;

import java.util.List;
import java.util.Optional;

/**
 * WorkingMemory 专用存储 SPI — 管理 WorkingMemory 和 ResumePoint 的持久化。
 * <p>
 * 默认 {@link #supports(MemoryType)} 返回 WORKING == true。
 * 实现类：InMemory（测试）、Redis（生产 L2 缓存）。
 */
public interface WorkingMemoryStore extends MemoryStore {

    @Override
    default boolean supports(MemoryType memoryType) {
        return memoryType == MemoryType.WORKING;
    }

    void save(WorkingMemory wm);

    Optional<WorkingMemory> load(MemoryId id);

    void saveResumePoint(MemoryId id, ResumePoint point);

    Optional<ResumePoint> loadResumePoint(MemoryId id, String label);

    List<ResumePoint> listResumePoints(MemoryId id);

    void delete(MemoryId id);
}
