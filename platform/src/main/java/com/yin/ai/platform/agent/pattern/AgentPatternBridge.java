package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.event.AgentEvent;
import reactor.core.publisher.Flux;
import com.yin.ai.platform.core.agent.mode.AgentMode;

/**
 * Compatibility seam used while the Spring-managed runtime delegates pattern
 * execution to the framework-neutral core.
 *
 * <p>The legacy runtime remains responsible for task persistence, SSE and
 * resume semantics. Implementations only replace a fresh pattern execution;
 * resumed executions continue through the legacy pattern until core exposes a
 * compatible checkpoint contract.</p>
 */
public interface AgentPatternBridge {

    /** Whether this fresh execution should be handled by the bridge. */
    boolean supports(AgentMode mode, AgentExecutionContext context, boolean resuming);

    /** Execute the selected mode and project its events into the legacy stream. */
    Flux<AgentEvent> execute(AgentMode mode, AgentExecutionContext context);
}
