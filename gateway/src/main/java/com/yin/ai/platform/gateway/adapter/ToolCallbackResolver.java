package com.yin.ai.platform.gateway.adapter;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Port the AI gateway uses to turn allowed function names into callable tools.
 * <p>
 * The gateway must not depend on the function registry implementation, because the registry
 * lives in a higher layer. Depending on this narrow port keeps the dependency direction
 * one-way: the registry implements it, the gateway consumes it.
 */
@FunctionalInterface
public interface ToolCallbackResolver {

    /**
     * Resolve the given function names into tool callbacks.
     *
     * @param functionNames allowed function names; may be null or empty
     * @return callbacks for the names that are known, available and callable; never null
     */
    List<ToolCallback> resolve(List<String> functionNames);
}
