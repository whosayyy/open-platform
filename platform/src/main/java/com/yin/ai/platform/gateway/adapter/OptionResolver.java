package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayOptions;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 选项解析器 SPI — 将 {@link GatewayOptions} 转换为 Provider 特定的 ChatOptions。
 */
public interface OptionResolver {

    /** 适配器名称（与 ModelAdapter.name() 对应） */
    String adapterName();

    /** 将 GatewayOptions 解析为 Provider 的 ChatOptions */
    ChatOptions resolve(GatewayOptions options);
}
