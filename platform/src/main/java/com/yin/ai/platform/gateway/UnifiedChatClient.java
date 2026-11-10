package com.yin.ai.platform.gateway;

import com.yin.ai.platform.engine.stream.StreamPacket;
import reactor.core.publisher.Flux;

/**
 * AI Gateway 统一入口。
 * <p>
 * 系统内唯一被允许持有 ChatClient / ChatModel 引用的组件。
 * 所有 LLM 调用必须通过此接口，禁止绕过 Gateway 直接调用 ChatClient。
 */
public interface UnifiedChatClient {

    /**
     * 阻塞式 chat。
     *
     * @param request 封装了 messages、functionNames、options 的请求对象
     * @return 统一响应（content、toolCalls、tokenUsage、latency 等）
     */
    GatewayResponse chat(GatewayRequest request);

    /**
     * 流式 chat。
     *
     * @param request 封装了 messages、functionNames、options 的请求对象
     * @return SSE 事件流
     */
    Flux<StreamPacket> chatStream(GatewayRequest request);
}
