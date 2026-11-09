package com.yin.ai.platform.core.agent.checkpoint;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe checkpoint store for pure Java consumers and tests. */
public final class InMemoryAgentCheckpointStore implements AgentCheckpointStore {

    private final ConcurrentHashMap<String, AgentCheckpoint> checkpoints =
            new ConcurrentHashMap<>();

    @Override
    public Optional<AgentCheckpoint> load(String taskId) {
        return Optional.ofNullable(checkpoints.get(taskId));
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("checkpoint must not be null");
        checkpoints.compute(checkpoint.taskId(), (taskId, current) ->
                current == null || checkpoint.revision() >= current.revision()
                        ? checkpoint : current);
    }

    @Override
    public void delete(String taskId) {
        if (taskId != null) checkpoints.remove(taskId);
    }
}
