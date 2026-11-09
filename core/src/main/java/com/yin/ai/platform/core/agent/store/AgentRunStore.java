package com.yin.ai.platform.core.agent.store;

import java.util.List;
import java.util.Optional;

/**
 * Core persistence port for agent task lifecycle state.
 *
 * <p>Implementations must create task ids atomically and reject stale updates.
 * Infrastructure adapters may persist the portable record in Redis, JDBC, or
 * another store without leaking those APIs into core.</p>
 */
public interface AgentRunStore {

    /** Creates revision zero and returns false when the task id already exists. */
    boolean create(AgentRunRecord record);

    /**
     * Saves exactly the next revision and returns false for missing or stale state.
     * The task id and parent task id are immutable after creation.
     */
    boolean update(AgentRunRecord record);

    Optional<AgentRunRecord> load(String taskId);

    List<AgentRunRecord> listByParent(String parentTaskId);
}
