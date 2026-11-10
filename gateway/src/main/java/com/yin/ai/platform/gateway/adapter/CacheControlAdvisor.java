package com.yin.ai.platform.gateway.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.MessageType;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 将 GatewayMessage.cacheControl 标记注入到 Spring AI ChatClient 的 advisor context。
 * <p>
 * DeepSeek/Anthropic API 支持 cache_control 来标记可缓存的内容块。此 Advisor
 * 在 context 中传递标记，由 {@link CacheControlHttpInterceptor} 负责将其转换为 API 请求格式。
 */
@Slf4j
public class CacheControlAdvisor implements CallAdvisor, StreamAdvisor {

    static final String METADATA_KEY_CACHE_CONTROL = "__cache_control__";

    private final Map<Integer, Boolean> cacheableByIndex;

    public CacheControlAdvisor(Map<Integer, Boolean> cacheableByIndex) {
        this.cacheableByIndex = new HashMap<>(cacheableByIndex);
    }

    @Override
    public String getName() {
        return "cache-control";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(enhanceRequest(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(enhanceRequest(request));
    }

    private ChatClientRequest enhanceRequest(ChatClientRequest request) {
        var messages = request.prompt().getInstructions();
        if (messages.isEmpty() || cacheableByIndex.isEmpty()) {
            return request;
        }

        int systemIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getMessageType() == MessageType.SYSTEM) {
                systemIndex = i;
                break;
            }
        }

        if (systemIndex >= 0 && cacheableByIndex.containsKey(systemIndex)) {
            Map<String, Object> metadata = new HashMap<>(request.context());
            metadata.put(METADATA_KEY_CACHE_CONTROL, cacheableByIndex.get(systemIndex));
            CacheControlContext.set(true);
            log.debug("[CACHE] marking message at index {} as cacheable", systemIndex);
            return request.mutate().context(metadata).build();
        }

        return request;
    }
}
