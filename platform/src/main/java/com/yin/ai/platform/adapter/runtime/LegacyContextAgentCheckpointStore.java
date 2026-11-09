package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpoint;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;

import java.util.Map;
import java.util.Optional;

/** Persists portable core checkpoints through the existing legacy context shell. */
public final class LegacyContextAgentCheckpointStore implements AgentCheckpointStore {

    public static final String CONTEXT_KEY = "core.checkpoint";

    private final AgentExecutionContext context;

    public LegacyContextAgentCheckpointStore(AgentExecutionContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context;
    }

    @Override
    public Optional<AgentCheckpoint> load(String taskId) {
        if (!context.getTaskId().equals(taskId)) return Optional.empty();
        Object raw = context.getVariable(CONTEXT_KEY);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Optional.empty();
        return Optional.ofNullable(AgentCheckpoint.fromPortableMap(map));
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null || !context.getTaskId().equals(checkpoint.taskId())) {
            throw new IllegalArgumentException("Checkpoint task does not match execution context");
        }
        context.putVariable(CONTEXT_KEY, checkpoint.toPortableMap());
        persistSnapshot();
    }

    @Override
    public void delete(String taskId) {
        if (!context.getTaskId().equals(taskId)) return;
        context.removeVariable(CONTEXT_KEY);
        persistSnapshot();
    }

    private void persistSnapshot() {
        if (context.getContextStore() != null) {
            context.getContextStore().saveSnapshot(context.getTaskId(), context.snapshot());
        }
    }
}
