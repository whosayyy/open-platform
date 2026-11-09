package com.yin.ai.platform.core.router.matcher;

import java.util.Map;

/**
 * 关键词→意图 映射提供者 SPI。
 * <p>
 * 业务模块实现此接口（或通过 YAML 配置驱动）提供关键词到意图标识符的映射。
 * Adapter/Starter 可从配置或外部配置中心提供映射。
 */
@FunctionalInterface
public interface KeywordIntentProvider {

    /**
     * 返回关键词（小写）→ 意图标识符 的映射。
     * 返回空 Map 时 KeywordMatcher 为无操作。
     */
    Map<String, String> getKeywordIntentMappings();
}
