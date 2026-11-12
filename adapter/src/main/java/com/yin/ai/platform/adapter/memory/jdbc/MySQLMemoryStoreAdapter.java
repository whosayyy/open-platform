package com.yin.ai.platform.adapter.memory.jdbc;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.MemoryVector;
import com.yin.ai.platform.core.memory.procedural.ProceduralMemoryStore;
import com.yin.ai.platform.core.memory.procedural.Procedure;
import com.yin.ai.platform.core.memory.semantic.Fact;
import com.yin.ai.platform.core.memory.semantic.SemanticMemoryStore;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * MySQL 存储适配器 — 基于 MyBatis-Plus 实现 Semantic + Procedural Memory 持久化。
 * <p>
 * 当前为骨架实现（Phase 4 仅定义 SPI + 适配器骨架，完整实现推迟到 Phase 5+）。
 * 当 {@code wkc-common} 中的 MyBatis-Plus 基础设施就绪后，填充具体 Mapper 调用。
 * <p>
 * 表设计（规划中）：
 * <ul>
 *   <li>{@code ai_fact} — subject/predicate/object/confidence/vector_json/metadata</li>
 *   <li>{@code ai_procedure} — id/goal/state/steps_json/total_executions/avg_duration_ms/tags</li>
 * </ul>
 */
@Slf4j
public class MySQLMemoryStoreAdapter implements SemanticMemoryStore, ProceduralMemoryStore {

    // ==================== MemoryStore ====================

    @Override
    public StoreType storeType() {
        return StoreType.MYSQL;
    }

    @Override
    public boolean supports(MemoryType memoryType) {
        return memoryType == MemoryType.SEMANTIC || memoryType == MemoryType.PROCEDURAL;
    }

    @Override
    public StoreHealth health() {
        return StoreHealth.unhealthy("MySQL adapter not yet implemented");
    }

    @Override
    public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
        log.warn("[MySQL] casWrite not implemented");
        return false;
    }

    // ==================== SemanticMemoryStore ====================

    @Override
    public Fact writeFact(Fact fact) {
        log.warn("[MySQL] writeFact not implemented");
        return fact;
    }

    @Override
    public List<Fact> writeFacts(List<Fact> facts) {
        log.warn("[MySQL] writeFacts not implemented");
        return facts;
    }

    @Override
    public List<Fact> queryFacts(String subject, String predicate, String object) {
        log.warn("[MySQL] queryFacts not implemented");
        return List.of();
    }

    @Override
    public List<Fact> searchByVector(MemoryVector queryVector, int topK) {
        log.warn("[MySQL] searchByVector not implemented");
        return List.of();
    }

    @Override
    public boolean deleteFact(String factId) {
        log.warn("[MySQL] deleteFact not implemented");
        return false;
    }

    @Override
    public void clearAll() {
        log.warn("[MySQL] clearAll not implemented");
    }

    @Override
    public long factCount() {
        return 0;
    }

    // ==================== ProceduralMemoryStore ====================

    @Override
    public Procedure save(Procedure procedure) {
        log.warn("[MySQL] save procedure not implemented");
        return procedure;
    }

    @Override
    public Optional<Procedure> load(String procedureId) {
        log.warn("[MySQL] load procedure not implemented");
        return Optional.empty();
    }

    @Override
    public List<Procedure> findByGoal(String goal) {
        log.warn("[MySQL] findByGoal not implemented");
        return List.of();
    }

    @Override
    public List<Procedure> findByTag(String tag) {
        log.warn("[MySQL] findByTag not implemented");
        return List.of();
    }

    @Override
    public List<Procedure> findActive() {
        log.warn("[MySQL] findActive not implemented");
        return List.of();
    }

    @Override
    public boolean delete(String procedureId) {
        log.warn("[MySQL] delete procedure not implemented");
        return false;
    }

    @Override
    public long count() {
        return 0;
    }
}
