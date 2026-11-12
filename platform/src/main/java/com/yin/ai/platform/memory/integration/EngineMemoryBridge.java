package com.yin.ai.platform.memory.integration;

import com.yin.ai.platform.memory.LegacyMemoryContextMapper;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.ResumePoint;
import com.yin.ai.platform.core.memory.working.WorkingMemory;

import java.util.List;
import java.util.Optional;

/**
 * 引擎 Memory 桥接 — ExecutionEngine 与 MemoryRuntime 之间的适配层。
 * <p>
 * 职责：
 * <ul>
 *   <li>任务执行前自动保存 Working Memory 快照</li>
 *   <li>任务完成后将结果写入 Episodic Memory</li>
 *   <li>故障时创建 ResumePoint 供恢复使用</li>
 *   <li>ExecutionContext ↔ MemoryContext 转换</li>
 * </ul>
 * <p>
 * 使用方式：Engine 在执行任务前后调用本 Bridge 的对应方法，
 * 无需直接操作 MemoryRuntime。
 */
public class EngineMemoryBridge {

    private final MemoryRuntime memoryRuntime;

    public EngineMemoryBridge(MemoryRuntime memoryRuntime) {
        this.memoryRuntime = memoryRuntime;
    }

    /**
     * 任务执行前 — 初始化 Working Memory。
     *
     * @return 已创建的 WorkingMemory（含 rootPlan）
     */
    public WorkingMemory beforeTask(ExecutionContext ctx) {
        MemoryContext memCtx = LegacyMemoryContextMapper.from(ctx);
        return memoryRuntime.getOrCreateWorkingMemory(memCtx);
    }

    /**
     * 任务执行后 — 将结果写入 Episodic Memory。
     *
     * @param wm     执行前的 WorkingMemory
     * @param result 执行结果摘要
     */
    public MemoryId afterTask(WorkingMemory wm, ExecutionContext ctx, String result) {
        MemoryId episodicId = MemoryId.of(
                wm.getId().id(),
                MemoryType.EPISODIC,
                wm.getId().ownerKey());
        memoryRuntime.append(episodicId, result, LegacyMemoryContextMapper.from(ctx));
        return episodicId;
    }

    /**
     * 在关键操作前创建 ResumePoint — 供后续故障恢复。
     *
     * @param wm    WorkingMemory
     * @param label 恢复点标签（如 "before_tool_call"）
     */
    public ResumePoint createResumePoint(WorkingMemory wm, String label) {
        return memoryRuntime.createResumePoint(wm.getId(), label);
    }

    /**
     * 任务失败时创建 ResumePoint 并保存 Working Memory。
     */
    public Optional<ResumePoint> onTaskFailure(WorkingMemory wm, Throwable error) {
        memoryRuntime.saveWorkingMemory(wm);
        String label = "error_" + System.currentTimeMillis();
        try {
            return Optional.of(memoryRuntime.createResumePoint(wm.getId(), label));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 从 ResumePoint 恢复 Working Memory。
     */
    public Optional<WorkingMemory> resumeFrom(WorkingMemory wm, String label,
                                               ExecutionContext ctx) {
        return memoryRuntime.resumeFromPoint(wm.getId(), label,
                LegacyMemoryContextMapper.from(ctx));
    }

    /**
     * 列出所有可用的 ResumePoint。
     */
    public List<String> listResumePoints(WorkingMemory wm) {
        return wm.getResumePoints().stream()
                .map(ResumePoint::label)
                .toList();
    }

    public MemoryRuntime memoryRuntime() {
        return memoryRuntime;
    }
}
