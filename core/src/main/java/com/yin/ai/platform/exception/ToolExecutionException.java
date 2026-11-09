package com.yin.ai.platform.exception;

public class ToolExecutionException extends RuntimeException {
    private final String toolName;

    public ToolExecutionException(String toolName, Throwable cause) {
        super("工具 [" + toolName + "] 执行失败: " + cause.getMessage(), cause);
        this.toolName = toolName;
    }

    public String getToolName() { return toolName; }
}
