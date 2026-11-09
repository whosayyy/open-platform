package com.yin.ai.platform.core.agent;

import java.util.concurrent.CompletionStage;

/** Handle returned immediately for an asynchronous agent execution. */
public interface AgentRun {

    String taskId();

    CompletionStage<AgentResult> completion();

    boolean cancel();
}
