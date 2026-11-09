package com.yin.ai.platform.workflow.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户在 WAITING_INPUT 时补充的输入。
 */
public record UserInput(
        String taskRunId,
        String content,
        Map<String, Object> payload
) {

    public static UserInput of(String taskRunId, String content) {
        return new UserInput(taskRunId, content, new HashMap<>());
    }
}
