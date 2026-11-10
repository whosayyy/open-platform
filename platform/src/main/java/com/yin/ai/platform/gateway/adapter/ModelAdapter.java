package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.engine.stream.StreamPacket;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * 模型适配器 SPI — 封装具体的 ChatClient / ChatModel 实现。
 * <p>
 * 每个 LLM Provider（DeepSeek, DashScope, OpenAI 等）对应一个 Adapter。
 */
public interface ModelAdapter {

    /** 适配器名称 */
    String name();

    /** 支持的模型列表 */
    Set<String> supportedModels();

    /** 阻塞式调用 */
    GatewayResponse call(GatewayRequest request);

    /** 流式调用 */
    Flux<StreamPacket> stream(GatewayRequest request);
}
