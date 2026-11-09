package com.yin.ai.platform.memory.session;

import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SessionMemoryStoreAdapter")
@ExtendWith(MockitoExtension.class)
class SessionMemoryStoreAdapterTest {

    @Mock
    private SessionMemoryManager sessionMemoryManager;

    private SessionMemoryStoreAdapter adapter;
    private final MemoryId memoryId = MemoryId.of("session-123", MemoryType.SESSION, "user-1");

    @BeforeEach
    void setUp() {
        adapter = new SessionMemoryStoreAdapter(sessionMemoryManager);
    }

    @Test
    @DisplayName("supports 仅接受 SESSION 类型")
    void shouldSupportOnlySessionType() {
        assertThat(adapter.supports(MemoryType.SESSION)).isTrue();
        assertThat(adapter.supports(MemoryType.EPISODIC)).isTrue();
        assertThat(adapter.supports(MemoryType.WORKING)).isFalse();
    }

    @Test
    @DisplayName("append 委托到 SessionMemoryManager.addMessage")
    void shouldDelegateAppend() {
        MemoryCell cell = MemoryCell.of(
                com.yin.ai.platform.core.memory.MemoryCellId.of("c1"),
                MemoryType.SESSION,
                "Hello, World!",
                Map.of("role", "user"));

        adapter.append(memoryId, cell);

        verify(sessionMemoryManager).addMessage(eq("session-123"),
                argThat(message -> "c1".equals(message.cellId())
                        && "user".equals(message.role())
                        && "Hello, World!".equals(message.content())));
    }

    @Test
    @DisplayName("append 默认 role 为 user")
    void shouldDefaultRoleToUser() {
        MemoryCell cell = MemoryCell.of(
                com.yin.ai.platform.core.memory.MemoryCellId.of("c1"),
                MemoryType.SESSION,
                "No role specified");

        adapter.append(memoryId, cell);

        verify(sessionMemoryManager).addMessage(eq("session-123"),
                argThat(message -> "user".equals(message.role())
                        && "No role specified".equals(message.content())));
    }

    @Test
    @DisplayName("readAll 从 SessionMemoryManager 读取并转换为 MemoryCell")
    void shouldReadAllAndConvert() {
        when(sessionMemoryManager.getHistory("session-123")).thenReturn(List.of(
                new SessionMemoryManager.ChatMessage("user", "Hi"),
                new SessionMemoryManager.ChatMessage("assistant", "Hello!")));

        List<MemoryCell> result = adapter.readAll(memoryId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("Hi");
        assertThat(result.get(0).attributes()).containsEntry("role", "user");
        assertThat(result.get(0).memoryType()).isEqualTo(MemoryType.SESSION);
        assertThat(result.get(1).content()).isEqualTo("Hello!");
        assertThat(result.get(1).attributes()).containsEntry("role", "assistant");
    }

    @Test
    @DisplayName("readTimeline 限制返回数量")
    void shouldLimitReadTimeline() {
        when(sessionMemoryManager.getHistory("session-123")).thenReturn(List.of(
                new SessionMemoryManager.ChatMessage("user", "msg1"),
                new SessionMemoryManager.ChatMessage("assistant", "msg2"),
                new SessionMemoryManager.ChatMessage("user", "msg3")));

        List<MemoryCell> result = adapter.readTimeline(memoryId, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("size 返回消息数量")
    void shouldReturnSize() {
        when(sessionMemoryManager.getHistory("session-123")).thenReturn(List.of(
                new SessionMemoryManager.ChatMessage("user", "a"),
                new SessionMemoryManager.ChatMessage("assistant", "b"),
                new SessionMemoryManager.ChatMessage("user", "c")));

        assertThat(adapter.size(memoryId)).isEqualTo(3);
    }

    @Test
    @DisplayName("deleteAll 委托到 clearSession")
    void shouldDelegateDeleteAll() {
        adapter.deleteAll(memoryId);
        verify(sessionMemoryManager).clearSession("session-123");
    }

    @Test
    @DisplayName("casWrite 返回 false（不支持 CAS）")
    void shouldReturnFalseForCasWrite() {
        MemoryCell cell = MemoryCell.of(
                com.yin.ai.platform.core.memory.MemoryCellId.of("c1"),
                MemoryType.SESSION, "data");
        assertThat(adapter.casWrite(memoryId, cell, 1)).isFalse();
    }

    @Test
    @DisplayName("appendBatch 批量委托")
    void shouldDelegateAppendBatch() {
        List<MemoryCell> cells = List.of(
                MemoryCell.of(com.yin.ai.platform.core.memory.MemoryCellId.of("c1"),
                        MemoryType.SESSION, "msg1", Map.of("role", "user")),
                MemoryCell.of(com.yin.ai.platform.core.memory.MemoryCellId.of("c2"),
                        MemoryType.SESSION, "msg2", Map.of("role", "assistant")));

        adapter.appendBatch(memoryId, cells);

        verify(sessionMemoryManager).addMessage(eq("session-123"),
                argThat(message -> "c1".equals(message.cellId()) && "user".equals(message.role())));
        verify(sessionMemoryManager).addMessage(eq("session-123"),
                argThat(message -> "c2".equals(message.cellId()) && "assistant".equals(message.role())));
    }
}
