package com.yin.ai.platform.memory.working;

import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;

import java.util.Optional;

/**
 * WorkingMemory 管理器 SPI — 管理 WorkingMemory 的完整生命周期。
 * <p>
 * 核心能力：create（新建）、save/load（持久化）、createResumePoint（断点）、
 * resume（故障恢复）、discard（清理）。
 * <p>
 * 实现通过 {@link com.yin.ai.platform.core.memory.store.WorkingMemoryStore} 与存储后端解耦。
 */
public interface WorkingMemoryManager {

    WorkingMemory create(MemoryContext ctx);

    Optional<WorkingMemory> load(MemoryId id);

    void save(WorkingMemory wm);

    void saveResumePoint(WorkingMemory wm, ResumePoint point);

    Optional<WorkingMemory> resume(MemoryId id, String resumePointLabel);

    void discard(MemoryId id);
}
