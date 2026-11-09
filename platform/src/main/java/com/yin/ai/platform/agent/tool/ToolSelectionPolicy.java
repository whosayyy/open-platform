package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.function.FunctionDefinition;

import java.util.Collection;
import java.util.Set;

public interface ToolSelectionPolicy {

    Selection select(String goal, Collection<FunctionDefinition> candidates,
                     Set<String> declaredTools, Set<String> requiredTools,
                     AgentContext context);

    record Selection(Collection<FunctionDefinition> tools, String strategy, boolean fallback) {}
}
