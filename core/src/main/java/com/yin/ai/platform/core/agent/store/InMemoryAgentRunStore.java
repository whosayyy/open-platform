package com.yin.ai.platform.core.agent.store;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe local AgentRunStore used by pure Java consumers and tests. */
public final class InMemoryAgentRunStore implements AgentRunStore {

    private final ConcurrentHashMap<String, AgentRunRecord> records =
            new ConcurrentHashMap<>();

    @Override
    public boolean create(AgentRunRecord record) {
        if (record == null) throw new IllegalArgumentException("record must not be null");
        if (record.revision() != 0L) {
            throw new IllegalArgumentException("new records must start at revision zero");
        }
        return records.putIfAbsent(record.taskId(), record) == null;
    }

    @Override
    public boolean update(AgentRunRecord record) {
        if (record == null) throw new IllegalArgumentException("record must not be null");
        AtomicBoolean updated = new AtomicBoolean();
        records.computeIfPresent(record.taskId(), (taskId, current) -> {
            if (record.revision() != current.revision() + 1L
                    || !Objects.equals(record.parentTaskId(), current.parentTaskId())) {
                return current;
            }
            updated.set(true);
            return record;
        });
        return updated.get();
    }

    @Override
    public Optional<AgentRunRecord> load(String taskId) {
        if (taskId == null || taskId.isBlank()) return Optional.empty();
        return Optional.ofNullable(records.get(taskId));
    }

    @Override
    public List<AgentRunRecord> listByParent(String parentTaskId) {
        if (parentTaskId == null || parentTaskId.isBlank()) return List.of();
        String normalizedParentTaskId = parentTaskId.trim();
        return records.values().stream()
                .filter(record -> normalizedParentTaskId.equals(record.parentTaskId()))
                .sorted(Comparator.comparing(AgentRunRecord::createdAt)
                        .thenComparing(AgentRunRecord::taskId))
                .toList();
    }
}
