package com.yin.ai.platform.core.agent;

/** Push-based core event listener; reactive and transport bridges adapt this interface. */
@FunctionalInterface
public interface AgentEventListener {

    AgentEventListener NOOP = event -> { };

    void onEvent(AgentEvent event);
}
