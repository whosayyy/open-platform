package com.yin.ai.platform.agent.memory;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.StepTrace;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.memory.SessionMemoryManager.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentMemoryHook")
class AgentMemoryHookTest {

    private StubSessionMemoryManager memoryManager;
    private AgentMemoryHook hook;
    private AgentContext ctx;
    private AgentContext ctxWithSession;

    @BeforeEach
    void setUp() {
        memoryManager = new StubSessionMemoryManager();
        hook = new AgentMemoryHook(memoryManager);

        ExecutionContext execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
        AgentConfig config = AgentConfig.defaults();
        AgentTask task = new AgentTask("test goal", Map.of(), null, config);
        ctx = new AgentContext(execCtx, task, 0);

        AgentTask sessionTask = new AgentTask("test goal",
                Map.of("sessionId", "session-123"), null, config);
        ctxWithSession = new AgentContext(execCtx, sessionTask, 0);
    }

    @Test
    @DisplayName("injectHistory with null sessionId does nothing")
    void injectHistoryNullSessionId() {
        hook.injectHistory(ctx);

        assertThat(ctx.messageCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("injectHistory injects messages from SessionMemoryManager")
    void injectHistoryWithSession() {
        memoryManager.addMessage("session-123", "user", "Hello");
        memoryManager.addMessage("session-123", "assistant", "Hi there");

        hook.injectHistory(ctxWithSession);

        assertThat(ctxWithSession.messageCount()).isEqualTo(2);
        assertThat(ctxWithSession.getMessages().get(0)).isEqualTo("user:Hello");
        assertThat(ctxWithSession.getMessages().get(1)).isEqualTo("assistant:Hi there");
    }

    @Test
    @DisplayName("injectHistory with empty history does nothing")
    void injectHistoryEmpty() {
        hook.injectHistory(ctxWithSession);

        assertThat(ctxWithSession.messageCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("recordStep with null sessionId does nothing")
    void recordStepNullSessionId() {
        hook.recordStep(ctx, "assistant", "some output");

        assertThat(memoryManager.getHistory("session-123")).isEmpty();
    }

    @Test
    @DisplayName("recordStep with valid sessionId persists to memory")
    void recordStepWithSession() {
        hook.recordStep(ctxWithSession, "assistant", "step result");

        List<ChatMessage> history = memoryManager.getHistory("session-123");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).role()).isEqualTo("assistant");
        assertThat(history.get(0).content()).isEqualTo("step result");
    }

    @Test
    @DisplayName("recordStep with null content is skipped")
    void recordStepNullContent() {
        hook.recordStep(ctxWithSession, "assistant", null);

        assertThat(memoryManager.getHistory("session-123")).isEmpty();
    }

    @Test
    @DisplayName("recordCompletion with null sessionId does nothing")
    void recordCompletionNullSession() {
        AgentResult result = AgentResult.ok("final", List.of(), 3);

        hook.recordCompletion(ctx, result);

        assertThat(memoryManager.getHistory("session-123")).isEmpty();
    }

    @Test
    @DisplayName("recordCompletion with failed result does nothing")
    void recordCompletionFailed() {
        AgentResult result = AgentResult.fail("error", List.of(), 3);

        hook.recordCompletion(ctxWithSession, result);

        assertThat(memoryManager.getHistory("session-123")).isEmpty();
    }

    @Test
    @DisplayName("recordCompletion persists final result")
    void recordCompletionSuccess() {
        AgentResult result = AgentResult.ok("final answer", List.of(), 5);

        hook.recordCompletion(ctxWithSession, result);

        List<ChatMessage> history = memoryManager.getHistory("session-123");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).role()).isEqualTo("assistant");
        assertThat(history.get(0).content()).isEqualTo("final answer");
    }

    @Test
    @DisplayName("recordCompletion with blank result does nothing")
    void recordCompletionBlankResult() {
        AgentResult result = AgentResult.ok("   ", List.of(), 5);

        hook.recordCompletion(ctxWithSession, result);

        assertThat(memoryManager.getHistory("session-123")).isEmpty();
    }

    // ——— Stub ———

    static class StubSessionMemoryManager extends SessionMemoryManager {
        private final List<ChatMessage> messages = new ArrayList<>();

        StubSessionMemoryManager() {
            super(null);
        }

        @Override
        public void addMessage(String sessionId, String role, String content) {
            messages.add(new ChatMessage(role, content));
        }

        @Override
        public List<ChatMessage> getHistory(String sessionId) {
            return new ArrayList<>(messages);
        }

        @Override
        public void clearSession(String sessionId) {
            messages.clear();
        }
    }
}
