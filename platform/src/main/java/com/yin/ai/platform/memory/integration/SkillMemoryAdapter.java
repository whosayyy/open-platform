package com.yin.ai.platform.memory.integration;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.WorkingMemory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Skill Memory 适配器 — 为 Skill 执行提供统一的 Memory 访问接口。
 * <p>
 * 类似 {@code PromptComposer} 使用 {@code SessionMemoryManager} 的模式，
 * 本适配器让 Skill 能够：
 * <ul>
 *   <li>读取上下文记忆（Episodic Timeline）</li>
 *   <li>保存中间结果为 Working Memory</li>
 *   <li>持久化关键事实到 Episodic Memory</li>
 *   <li>搜索历史记忆</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 *   SkillMemoryAdapter memory = new SkillMemoryAdapter(memoryRuntime);
 *   WorkingMemory wm = memory.createWorkingMemory(ctx);
 *   memory.remember(wm.getId(), "关键发现: ...", ctx);
 *   List<MemoryCell> history = memory.recall(wm.getId(), 10);
 * }</pre>
 */
public class SkillMemoryAdapter {

    private final MemoryRuntime memoryRuntime;

    public SkillMemoryAdapter(MemoryRuntime memoryRuntime) {
        this.memoryRuntime = memoryRuntime;
    }

    /**
     * 为 Skill 创建 Working Memory。
     */
    public WorkingMemory createWorkingMemory(MemoryContext ctx) {
        return memoryRuntime.getOrCreateWorkingMemory(ctx);
    }

    /**
     * 加载已有的 Working Memory。
     */
    public Optional<WorkingMemory> loadWorkingMemory(MemoryId id) {
        return memoryRuntime.loadWorkingMemory(id);
    }

    /**
     * 保存当前 Working Memory 状态。
     */
    public void saveWorkingMemory(WorkingMemory wm) {
        memoryRuntime.saveWorkingMemory(wm);
    }

    /**
     * 将一条记忆写入 Episodic Memory。
     */
    public MemoryCell remember(MemoryId episodicId, String content, MemoryContext ctx) {
        return memoryRuntime.append(episodicId, content, ctx);
    }

    /**
     * 批量写入记忆。
     */
    public List<MemoryCell> rememberBatch(MemoryId episodicId, List<MemoryCell> cells,
                                           MemoryContext ctx) {
        return memoryRuntime.appendBatch(episodicId, cells, ctx);
    }

    /**
     * 召回最近 N 条记忆（Timeline 读取）。
     */
    public List<MemoryCell> recall(MemoryId episodicId, int limit) {
        return memoryRuntime.readTimeline(episodicId, limit);
    }

    /**
     * 搜索相关记忆。
     */
    public QueryResult search(String query, MemoryContext ctx) {
        return memoryRuntime.retrieve(
                MemoryQuery.byText(query, 10), ctx);
    }

    /**
     * 为 Skill 创建专属的 Episodic Memory ID。
     */
    public MemoryId createEpisodicId(String skillName, String ownerKey) {
        return MemoryId.of("skill:" + skillName + ":" + UUID.randomUUID().toString().substring(0, 8),
                MemoryType.EPISODIC, ownerKey);
    }

    /**
     * 从文本中创建一个 MemoryCell。
     */
    public MemoryCell cellFrom(String content, MemoryType type) {
        return MemoryCell.of(
                com.yin.ai.platform.core.memory.MemoryCellId.of(
                        UUID.randomUUID().toString()),
                type, content);
    }

    public MemoryRuntime memoryRuntime() {
        return memoryRuntime;
    }
}
