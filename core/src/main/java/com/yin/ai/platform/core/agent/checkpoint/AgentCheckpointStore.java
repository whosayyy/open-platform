package com.yin.ai.platform.core.agent.checkpoint;

import java.util.Optional;

/** Persistence port for resumable core pattern state. */
public interface AgentCheckpointStore {

    AgentCheckpointStore NONE = new AgentCheckpointStore() {
        @Override public Optional<AgentCheckpoint> load(String taskId) { return Optional.empty(); }
        @Override public void save(AgentCheckpoint checkpoint) { }
        @Override public void delete(String taskId) { }
    };

    Optional<AgentCheckpoint> load(String taskId);

    void save(AgentCheckpoint checkpoint);

    void delete(String taskId);
}
