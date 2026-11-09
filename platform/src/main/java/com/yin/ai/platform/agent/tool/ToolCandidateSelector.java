package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.function.FunctionDefinition;

import java.util.Collection;
import java.util.Set;

/** Optional expensive selector; it is invoked only for a large, already-authorized candidate set. */
public interface ToolCandidateSelector {

    Set<String> select(String goal, Collection<FunctionDefinition> candidates, AgentContext context);
}
