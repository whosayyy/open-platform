package com.yin.ai.platform.gateway.adapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheControlAdvisor")
class CacheControlAdvisorTest {

    private CacheControlAdvisor advisor;
    private boolean called;
    private Map<String, Object> capturedContext;

    @BeforeEach
    void setUp() {
        advisor = new CacheControlAdvisor(Map.of(0, true));
        called = false;
        capturedContext = null;
    }

    @AfterEach
    void tearDown() {
        CacheControlContext.clear();
    }

    @Test
    @DisplayName("系统消息在 cacheableByIndex 中时，注入 context 并设置 CacheControlContext")
    void shouldMarkSystemMessageAsCacheable() {
        List<Message> messages = List.of(
                new SystemMessage("You are helpful"),
                new UserMessage("Hello")
        );
        ChatClientRequest request = new ChatClientRequest(new Prompt(messages), Map.of());

        CallAdvisorChain chain = new StubCallAdvisorChain(r -> {
            called = true;
            capturedContext = r.context();
            return ChatClientResponse.builder().chatResponse(null).build();
        });

        advisor.adviseCall(request, chain);
        assertThat(called).isTrue();
        assertThat(capturedContext).containsEntry(
                CacheControlAdvisor.METADATA_KEY_CACHE_CONTROL, true);
        assertThat(CacheControlContext.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("无系统消息时不注入标记")
    void shouldNotMarkWhenNoSystemMessage() {
        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new UserMessage("World")
        );
        ChatClientRequest request = new ChatClientRequest(new Prompt(messages), Map.of());

        CallAdvisorChain chain = new StubCallAdvisorChain(r -> {
            called = true;
            capturedContext = r.context();
            return ChatClientResponse.builder().chatResponse(null).build();
        });

        advisor.adviseCall(request, chain);
        assertThat(called).isTrue();
        assertThat(capturedContext).doesNotContainKey(
                CacheControlAdvisor.METADATA_KEY_CACHE_CONTROL);
    }

    @Test
    @DisplayName("cacheableByIndex 为空时不注入")
    void shouldNotMarkWhenCacheableByIndexEmpty() {
        advisor = new CacheControlAdvisor(Map.of());
        List<Message> messages = List.of(
                new SystemMessage("You are helpful"),
                new UserMessage("Hello")
        );
        ChatClientRequest request = new ChatClientRequest(new Prompt(messages), Map.of());

        CallAdvisorChain chain = new StubCallAdvisorChain(r -> {
            called = true;
            capturedContext = r.context();
            return ChatClientResponse.builder().chatResponse(null).build();
        });

        advisor.adviseCall(request, chain);
        assertThat(called).isTrue();
        assertThat(capturedContext).doesNotContainKey(
                CacheControlAdvisor.METADATA_KEY_CACHE_CONTROL);
    }

    @Test
    @DisplayName("系统消息不在 cacheableByIndex 中时跳过")
    void shouldSkipSystemMessageNotInMap() {
        advisor = new CacheControlAdvisor(Map.of(1, true));
        List<Message> messages = List.of(
                new SystemMessage("You are helpful"),
                new UserMessage("Hello")
        );
        ChatClientRequest request = new ChatClientRequest(new Prompt(messages), Map.of());

        CallAdvisorChain chain = new StubCallAdvisorChain(r -> {
            called = true;
            capturedContext = r.context();
            return ChatClientResponse.builder().chatResponse(null).build();
        });

        advisor.adviseCall(request, chain);
        assertThat(called).isTrue();
        assertThat(capturedContext).doesNotContainKey(
                CacheControlAdvisor.METADATA_KEY_CACHE_CONTROL);
    }

    @Test
    @DisplayName("getName 返回 cache-control")
    void shouldReturnCorrectName() {
        assertThat(advisor.getName()).isEqualTo("cache-control");
    }

    private interface CallChainHandler {
        ChatClientResponse handle(ChatClientRequest request);
    }

    private static class StubCallAdvisorChain implements CallAdvisorChain {
        private final CallChainHandler handler;
        StubCallAdvisorChain(CallChainHandler handler) { this.handler = handler; }
        @Override public ChatClientResponse nextCall(ChatClientRequest r) { return handler.handle(r); }
        @Override public List<CallAdvisor> getCallAdvisors() { return List.of(); }
        @Override public CallAdvisorChain copy(CallAdvisor after) { return this; }
    }
}
