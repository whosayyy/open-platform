package com.yin.ai.platform.gateway;

import com.yin.ai.platform.gateway.filter.GatewayFilterChainExecutor;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 默认 UnifiedChatClient 实现。
 * <p>
 * 系统内唯一持有 {@link org.springframework.ai.chat.client.ChatClient.Builder} 引用的组件。
 * 所有 LLM 调用经过 Filter Chain → ModelAdapter → LLM 的完整管道。
 */
@Slf4j
@Component
public class DefaultUnifiedChatClient implements UnifiedChatClient {

    private final GatewayFilterChainExecutor filterChainExecutor;

    public DefaultUnifiedChatClient(GatewayFilterChainExecutor filterChainExecutor) {
        this.filterChainExecutor = filterChainExecutor;
    }

    @Override
    public GatewayResponse chat(GatewayRequest request) {
        long start = System.currentTimeMillis();

        try {
            GatewayResponse response = filterChainExecutor.execute(request);

            long elapsed = System.currentTimeMillis() - start;
            log.debug("[GW] traceId={} caller={} model={} latencyMs={}",
                    request.getTraceId(), request.getCaller(),
                    response.getModel(), elapsed);
            return response;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[GW] traceId={} caller={} error={} latencyMs={}",
                    request.getTraceId(), request.getCaller(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getName(), elapsed);
            throw e;
        }
    }

    @Override
    public Flux<StreamPacket> chatStream(GatewayRequest request) {
        return filterChainExecutor.executeStream(request);
    }
}
