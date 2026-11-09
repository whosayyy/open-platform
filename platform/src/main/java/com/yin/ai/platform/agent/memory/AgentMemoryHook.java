package com.yin.ai.platform.agent.memory;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.memory.SessionMemoryManager.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Agent 记忆钩子 — 将 Agent 执行过程中的消息同步到 SessionMemoryManager。
 * <p>
 * 可选组件：未注入时 Agent 独立运行，不持久化记忆。
 * 注入后每次 step 完成和 Agent 结束时自动记录。
 *
 * @deprecated 使用 {@link AgentMemoryBridge} 替代，后者接入完整的 {@link com.yin.ai.platform.core.memory.api.MemoryRuntime}
 *             五维记忆体系（Working / Episodic / Semantic / Procedural / Session）。
 */
@Deprecated
public class AgentMemoryHook {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryHook.class);

    private final SessionMemoryManager sessionMemoryManager;

    public AgentMemoryHook(SessionMemoryManager sessionMemoryManager) {
        this.sessionMemoryManager = sessionMemoryManager;
    }

    /** 注入对话历史到 AgentContext 的消息列表。 */
    public void injectHistory(AgentContext ctx) {
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            return;
        }
        List<ChatMessage> history = sessionMemoryManager.getHistory(sessionId);
        for (ChatMessage msg : history) {
            ctx.addMessage(msg.role(), msg.content());
        }
        if (!history.isEmpty()) {
            log.info("[AGENT-MEMORY] agentId={} injected {} history messages", ctx.getAgentId(), history.size());
        }
    }

    /** 记录单步执行结果。 */
    public void recordStep(AgentContext ctx, String role, String content) {
        String sessionId = ctx.getSessionId();
        if (sessionId == null || content == null) {
            return;
        }
        sessionMemoryManager.addMessage(sessionId, role, content);
    }

    /** Agent 完成时记录最终结果。 */
    public void recordCompletion(AgentContext ctx, AgentResult result) {
        String sessionId = ctx.getSessionId();
        if (sessionId == null || !result.success()) {
            return;
        }
        if (result.result() != null && !result.result().isBlank()) {
            sessionMemoryManager.addMessage(sessionId, "assistant", result.result());
        }
    }
}
