package com.yin.ai.platform.workflow.prompt;

import java.util.Map;
import java.util.Set;

/**
 * 已构建的步骤提示词上下文 — 只包含当前步骤需要的内容。
 */
public record StepContext(
        String systemPrompt,
        String userPrompt,
        Set<String> allowedTools,
        Map<String, Object> variables
) {
}
