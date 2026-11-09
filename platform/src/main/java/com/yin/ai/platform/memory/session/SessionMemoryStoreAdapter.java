package com.yin.ai.platform.memory.session;

import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.core.memory.store.StoreHealth;
import com.yin.ai.platform.core.memory.store.StoreType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session Memory → EpisodicMemoryStore 适配器。
 * <p>
 * 将 {@link SessionMemoryManager}（ChatMessage + Redis RList）适配为
 * 标准 {@link EpisodicMemoryStore} 接口，使 Session 类型的 Memory 可以
 * 通过 Memory Runtime 统一管理。
 * <p>
 * 映射关系：
 * <ul>
 *   <li>{@code MemoryId.id} → {@code sessionId}</li>
 *   <li>{@code ChatMessage} → {@code MemoryCell}（role 存入 attributes）</li>
 *   <li>{@code RList<String>} → 追加式 Timeline</li>
 * </ul>
 * <p>
 * 注意：本适配器不替代 {@link SessionMemoryManager}，而是作为平行入口。
 * 已有直接使用 SessionMemoryManager 的代码无需修改。
 */
@Slf4j
public class SessionMemoryStoreAdapter implements EpisodicMemoryStore {

    private final SessionMemoryManager sessionMemoryManager;
    private final AtomicLong globalSequence = new AtomicLong(0);

    public SessionMemoryStoreAdapter(SessionMemoryManager sessionMemoryManager) {
        this.sessionMemoryManager = sessionMemoryManager;
    }

    @Override
    public boolean supports(MemoryType memoryType) {
        return memoryType == MemoryType.SESSION || memoryType == MemoryType.EPISODIC;
    }

    @Override
    public StoreType storeType() {
        return StoreType.REDIS;
    }

    @Override
    public StoreHealth health() {
        return StoreHealth.healthy(0);
    }

    @Override
    public MemoryCell append(MemoryId id, MemoryCell cell) {
        String sessionId = id.id();
        // 从 content 或 attributes 中提取 role
        String role = (String) cell.attributes().getOrDefault("role", "user");
        Map<String, Object> attributes = new java.util.HashMap<>(cell.attributes());
        attributes.putIfAbsent("memoryScope", "SESSION");
        attributes.putIfAbsent("sessionId", sessionId);
        attributes.putIfAbsent("scopeId", sessionId);
        sessionMemoryManager.addMessage(sessionId, new SessionMemoryManager.ChatMessage(
                cell.cellId().id(), role, cell.content(), cell.timestamp(), attributes));
        return cell.withSequenceNumber(globalSequence.incrementAndGet());
    }

    @Override
    public List<MemoryCell> appendBatch(MemoryId id, List<MemoryCell> cells) {
        List<MemoryCell> result = new ArrayList<>();
        for (MemoryCell cell : cells) {
            result.add(append(id, cell));
        }
        return result;
    }

    @Override
    public List<MemoryCell> readTimeline(MemoryId id, int limit) {
        List<SessionMemoryManager.ChatMessage> history = sessionMemoryManager.getHistory(id.id());
        return toMemoryCells(history, id).stream()
                .limit(limit > 0 ? limit : history.size())
                .toList();
    }

    @Override
    public List<MemoryCell> readAll(MemoryId id) {
        List<SessionMemoryManager.ChatMessage> history = sessionMemoryManager.getHistory(id.id());
        return toMemoryCells(history, id);
    }

    @Override
    public long size(MemoryId id) {
        return sessionMemoryManager.getHistory(id.id()).size();
    }

    @Override
    public void deleteAll(MemoryId id) {
        sessionMemoryManager.clearSession(id.id());
    }

    @Override
    public boolean casWrite(MemoryId id, MemoryCell cell, int expectedVersion) {
        // Session memory 是追加式的，不支持 CAS 更新已有 Cell
        return false;
    }

    // ==================== private helpers ====================

    private List<MemoryCell> toMemoryCells(List<SessionMemoryManager.ChatMessage> messages,
                                            MemoryId memoryId) {
        List<MemoryCell> cells = new ArrayList<>();
        long seq = globalSequence.get();
        for (SessionMemoryManager.ChatMessage msg : messages) {
            String cellId = msg.cellId() != null ? msg.cellId() : UUID.randomUUID().toString();
            Map<String, Object> attributes = new java.util.HashMap<>(msg.attributes());
            attributes.putIfAbsent("role", msg.role());
            attributes.putIfAbsent("memoryScope", "SESSION");
            attributes.putIfAbsent("sessionId", memoryId.id());
            attributes.putIfAbsent("scopeId", memoryId.id());
            MemoryType type = memoryId.type() == MemoryType.SESSION
                    ? MemoryType.SESSION : MemoryType.EPISODIC;
            MemoryCell cell = new MemoryCell(MemoryCellId.of(cellId), type, msg.content(),
                    attributes, msg.timestamp() != null ? msg.timestamp() : Instant.now(),
                    0, java.util.Optional.empty(), Map.of(), null, 1);
            cell = cell.withSequenceNumber(seq++);
            cells.add(cell);
        }
        return cells;
    }
}
