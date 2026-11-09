package com.yin.ai.platform.core.tool;

/** External capability available to agents and workflows. */
public interface Tool {

    ToolDefinition definition();

    ToolResult execute(ToolInput input);
}
