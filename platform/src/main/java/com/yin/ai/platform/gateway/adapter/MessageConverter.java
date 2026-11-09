package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayMessage;

/**
 * 消息转换器 SPI — 将 {@link GatewayMessage} 转换为 Provider 特定的消息类型。
 */
public interface MessageConverter<T> {

    /** 适配器名称（与 ModelAdapter.name() 对应） */
    String adapterName();

    /** 将 GatewayMessage 转换为 Provider 消息 */
    T convert(GatewayMessage message);
}
