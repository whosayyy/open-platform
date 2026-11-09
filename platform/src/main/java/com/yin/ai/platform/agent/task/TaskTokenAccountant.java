package com.yin.ai.platform.agent.task;

/** Tracks and enforces token consumption across an entire agent task. */
public interface TaskTokenAccountant {

    void setBudget(String taskId, TaskTokenBudget budget);

    void record(String taskId, String stepId, String model, long totalTokens);

    TaskTokenSummary getSummary(String taskId);

    boolean isOverBudget(String taskId);

    boolean canExecuteStep(String taskId, String stepId);

    void cleanup(String taskId);
}
