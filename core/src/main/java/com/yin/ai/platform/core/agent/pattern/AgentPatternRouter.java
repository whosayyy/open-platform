package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.agent.AgentRequest;

/** Selects an orchestration policy without exposing a web or DI framework. */
@FunctionalInterface
public interface AgentPatternRouter {

    AgentMode route(AgentRequest request);
}
