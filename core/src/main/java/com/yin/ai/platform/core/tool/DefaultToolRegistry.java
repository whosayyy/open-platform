package com.yin.ai.platform.core.tool;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe registry that can be used without an application container. */
public final class DefaultToolRegistry implements ToolRegistry {

    private final ConcurrentMap<String, Tool> tools = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        String name = tool.definition().name();
        Tool previous = tools.putIfAbsent(name, tool);
        if (previous != null && previous != tool) {
            throw new IllegalStateException("Tool already registered: " + name);
        }
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(Tool::definition)
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }
}
