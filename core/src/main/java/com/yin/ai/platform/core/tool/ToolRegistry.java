package com.yin.ai.platform.core.tool;

import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {

    void register(Tool tool);

    Optional<Tool> find(String name);

    Collection<ToolDefinition> definitions();
}
