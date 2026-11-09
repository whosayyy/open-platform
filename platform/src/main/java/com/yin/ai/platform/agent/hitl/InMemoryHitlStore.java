package com.yin.ai.platform.agent.hitl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 HITL 存储 — 单节点 fallback，无 Redis 时使用。
 */
public class InMemoryHitlStore implements HitlStore {

    private final ConcurrentHashMap<String, HitlCheckpoint> store = new ConcurrentHashMap<>();

    @Override
    public void save(HitlCheckpoint checkpoint) {
        store.put(checkpoint.taskId(), checkpoint);
    }

    @Override
    public Optional<HitlCheckpoint> getPendingByTask(String taskId) {
        HitlCheckpoint cp = store.get(taskId);
        if (cp != null && cp.isExpired()) {
            store.remove(taskId);
            return Optional.empty();
        }
        return Optional.ofNullable(cp);
    }

    @Override
    public List<HitlCheckpoint> getPending() {
        List<HitlCheckpoint> result = new ArrayList<>();
        for (var entry : store.entrySet()) {
            if (entry.getValue().isExpired()) {
                store.remove(entry.getKey());
            } else {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    @Override
    public void remove(String taskId) {
        store.remove(taskId);
    }

    @Override
    public int pendingCount() {
        return (int) store.values().stream().filter(cp -> !cp.isExpired()).count();
    }
}
