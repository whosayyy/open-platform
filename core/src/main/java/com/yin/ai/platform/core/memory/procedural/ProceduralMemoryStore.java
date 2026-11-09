package com.yin.ai.platform.core.memory.procedural;

import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.store.MemoryStore;

import java.util.List;
import java.util.Optional;

/**
 * Procedural Memory 专用存储 SPI — 管理可复用任务过程（Procedure）的持久化。
 * <p>
 * Procedure 的存储需求有别于 Episodic（时间线）和 Semantic（事实/向量）：
 * <ul>
 *   <li>以 Procedure 为单位 CRUD，非 Cell 级别</li>
 *   <li>按 goal/tag 查询，支持 Exact/Partial 匹配</li>
 *   <li>维护执行统计（totalExecutions、avgDurationMs）</li>
 * </ul>
 */
public interface ProceduralMemoryStore extends MemoryStore {

    @Override
    default boolean supports(MemoryType memoryType) {
        return memoryType == MemoryType.PROCEDURAL;
    }

    /** 保存 Procedure */
    Procedure save(Procedure procedure);

    /** 按 ID 加载 */
    Optional<Procedure> load(String procedureId);

    /** 按 goal 查询（精确匹配） */
    List<Procedure> findByGoal(String goal);

    /** 按 tag 查询 */
    List<Procedure> findByTag(String tag);

    /** 查询所有 ACTIVE 状态的 Procedure */
    List<Procedure> findActive();

    /** 删除 */
    boolean delete(String procedureId);

    /** 总数 */
    long count();
}
