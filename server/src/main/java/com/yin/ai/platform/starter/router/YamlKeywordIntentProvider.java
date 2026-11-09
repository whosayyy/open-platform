package com.yin.ai.platform.starter.router;

import com.yin.ai.platform.core.router.matcher.KeywordIntentProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 基于 YAML 配置的关键词→意图映射提供者。
 * <p>
 * 从 {@link RouterProperties#getKeywordMappings()} 读取映射表。
 * 业务模块在 {@code application.yml} 中配置 {@code ai.router.keyword-mappings}。
 */
@Slf4j
public class YamlKeywordIntentProvider implements KeywordIntentProvider {

    private final Map<String, String> mappings;

    public YamlKeywordIntentProvider(RouterProperties properties) {
        this.mappings = properties.getKeywordMappings() != null
                ? Map.copyOf(properties.getKeywordMappings())
                : Map.of();
        log.info("[KEYWORD-PROVIDER] loaded {} keyword mappings from config", mappings.size());
    }

    @Override
    public Map<String, String> getKeywordIntentMappings() {
        return mappings;
    }
}
