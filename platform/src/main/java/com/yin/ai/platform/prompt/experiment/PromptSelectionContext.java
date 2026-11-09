package com.yin.ai.platform.prompt.experiment;

/** Non-sensitive stable identifiers used for sticky prompt assignment. */
public record PromptSelectionContext(String tenantId, String userId, String sessionId) {
}
