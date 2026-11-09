package com.yin.ai.platform.core.memory.procedural;

import java.util.List;
import java.util.Optional;

/**
 * Procedure 库 — Procedural Memory 的高层查询接口。
 * <p>
 * 与 {@link ProceduralMemoryStore} 的关系：Library 专注于"匹配和推荐"，
 * Store 专注于持久化。Library 委托到 Store 进行实际存储操作，
 * 同时组合 {@link ProcedureMatcher} 提供智能匹配能力。
 * <p>
 * 典型使用流程：
 * <pre>
 *   // 学习
 *   Procedure proc = procedureLibrary.learn(taskResult);
 *   // 或手动
 *   Procedure proc = Procedure.record("id-1", "写单元测试");
 *   procedureLibrary.save(proc.withSteps(steps));
 *
 *   // 复用
 *   List<Procedure> matches = procedureLibrary.match("写单元测试", 3);
 *   if (!matches.isEmpty()) { ... 复用步骤 ... }
 * </pre>
 */
public interface ProcedureLibrary {

    /** 保存 Procedure（状态为 RECORDING → 走验证流程，ACTIVE → 直接存储） */
    Procedure save(Procedure procedure);

    /** 按 ID 加载 */
    Optional<Procedure> load(String procedureId);

    /** 按 goal 精确匹配 */
    List<Procedure> findByGoal(String goal);

    /** 按 goal 匹配 top-K 最相似的 Procedure（委托到 ProcedureMatcher） */
    List<Procedure> match(String goal, int topK);

    /** 列出所有 ACTIVE 的 Procedure */
    List<Procedure> listActive();

    /** 删除 Procedure */
    boolean delete(String procedureId);

    /** Procedure 总数 */
    long count();
}
