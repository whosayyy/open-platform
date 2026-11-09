package com.yin.ai.platform.runtime;

import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.memory.SessionMemoryManager;

/**
 * 对话管理器 — 包装 SessionMemoryManager，提供 Skill 感知的对话历史管理。
 */
public class ConversationManager {

    private final SessionMemoryManager sessionMemoryManager;

    public ConversationManager(SessionMemoryManager sessionMemoryManager) {
        this.sessionMemoryManager = sessionMemoryManager;
    }

    /**
     * 记录用户消息。
     */
    public void recordUserMessage(String sessionId, String content) {
        sessionMemoryManager.addMessage(sessionId, "user", content);
    }

    /**
     * 记录助手回复。
     */
    public void recordAssistantMessage(String sessionId, String content) {
        sessionMemoryManager.addMessage(sessionId, "assistant", content);
    }

    /**
     * 根据 Skill 配置检查是否应该启用记忆并获取对话上下文。
     */
    public String getContextIfEnabled(SkillManifest manifest, String sessionId) {
        if (manifest.getMemory() != null && manifest.getMemory().isEnabled()
                && sessionId != null) {
            return sessionMemoryManager.buildConversationContext(sessionId);
        }
        return "";
    }

    /**
     * 清除会话记忆。
     */
    public void clear(String sessionId) {
        sessionMemoryManager.clearSession(sessionId);
    }

    /**
     * 设置会话归属用户。
     */
    public void setSessionOwner(String sessionId, Long userId) {
        sessionMemoryManager.setSessionOwner(sessionId, userId);
    }
}
