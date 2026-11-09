package com.yin.ai.platform.workflow.runtime;

/** Durable guard for workflow steps that perform external side effects. */
public interface StepIdempotencyStore {

    BeginResult begin(String taskRunId, String stepId);

    String loadCommittedResult(String taskRunId, String stepId);

    void commit(String taskRunId, String stepId, String serializedResult);

    void release(String taskRunId, String stepId);

    enum BeginResult {
        ACQUIRED,
        COMMITTED,
        IN_PROGRESS
    }
}
