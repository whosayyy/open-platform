package com.yin.ai.platform.memory.working;

import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.PlanNode;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import com.yin.ai.platform.core.memory.working.WorkingMemoryState;
import com.yin.ai.platform.memory.exception.MemoryNotFoundException;
import com.yin.ai.platform.core.memory.store.WorkingMemoryStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * WorkingMemoryManager 默认实现 — 委托 {@link WorkingMemoryStore} 完成持久化。
 * <p>
 * resume 流程：加载 ResumePoint → 加载 WorkingMemory → CAS 状态切换到 RESUMING →
 * 回填变量 → 切换到 ACTIVE。任一步骤失败返回 empty。
 */
public class DefaultWorkingMemoryManager implements WorkingMemoryManager {

    private final WorkingMemoryStore store;

    public DefaultWorkingMemoryManager(WorkingMemoryStore store) {
        this.store = store;
    }

    @Override
    public WorkingMemory create(MemoryContext ctx) {
        String id = UUID.randomUUID().toString();
        String ownerKey = ctx.getUserId().map(Object::toString).orElse("anonymous");
        MemoryId memoryId = MemoryId.of(id, MemoryType.WORKING, ownerKey);
        PlanNode rootPlan = PlanNode.goal("root", "Task execution");
        WorkingMemory wm = new WorkingMemory(memoryId, rootPlan);
        store.save(wm);
        return wm;
    }

    @Override
    public Optional<WorkingMemory> load(MemoryId id) {
        return store.load(id);
    }

    @Override
    public void save(WorkingMemory wm) {
        store.save(wm);
    }

    @Override
    public void saveResumePoint(WorkingMemory wm, ResumePoint point) {
        store.saveResumePoint(wm.getId(), point);
    }

    @Override
    public Optional<WorkingMemory> resume(MemoryId id, String resumePointLabel) {
        Optional<ResumePoint> point = store.loadResumePoint(id, resumePointLabel);
        if (point.isEmpty()) {
            return Optional.empty();
        }
        Optional<WorkingMemory> wmOpt = store.load(id);
        if (wmOpt.isEmpty()) {
            throw new MemoryNotFoundException("WorkingMemory not found for resume: " + id);
        }
        WorkingMemory wm = wmOpt.get();
        if (!wm.transitionTo(WorkingMemoryState.RESUMING)) {
            return Optional.empty();
        }
        ResumePoint rp = point.get();
        for (Map.Entry<String, Object> entry : rp.variables().entrySet()) {
            wm.putVariable(entry.getKey(), entry.getValue());
        }
        wm.transitionTo(WorkingMemoryState.ACTIVE);
        return Optional.of(wm);
    }

    @Override
    public void discard(MemoryId id) {
        store.delete(id);
    }
}
