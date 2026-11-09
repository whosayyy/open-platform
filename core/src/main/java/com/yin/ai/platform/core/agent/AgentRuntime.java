package com.yin.ai.platform.core.agent;

import java.util.List;
import java.util.Optional;

/** Canonical, framework-neutral entry point for every agent pattern. */
public interface AgentRuntime {

    AgentResult execute(AgentRequest request);

    AgentRun submit(AgentRequest request, AgentEventListener listener);

    AgentRun resume(AgentResumeRequest request, AgentEventListener listener);

    boolean cancel(String taskId);

    Optional<AgentSnapshot> snapshot(String taskId);

    List<AgentSnapshot> listChildren(String parentTaskId);

    void queueInstruction(String taskId, AgentInstruction instruction);
}
