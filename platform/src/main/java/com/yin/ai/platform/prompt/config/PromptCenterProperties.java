package com.yin.ai.platform.prompt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prompt Center 配置属性 — 绑定 {@code ai.prompt.*} 配置前缀。
 */
@Data
@ConfigurationProperties(prefix = "ai.prompt")
public class PromptCenterProperties {

    /** 存储类型：yaml | database | nacos */
    private String storeType = "yaml";

    /** YAML 存储路径（classpath） */
    private String storePath = "classpath:/prompts/";

    /** 是否启用缓存 */
    private boolean cacheEnabled = true;

    /** L1 Caffeine 缓存 TTL（秒） */
    private int cacheTtlSeconds = 300;

    /** L2 Redis 缓存 TTL（秒） */
    private int redisCacheTtlSeconds = 600;

    /** 是否启用热加载 */
    private boolean hotReload = true;

    /** 热加载轮询间隔 */
    private String hotReloadInterval = "30s";

    /** 严格模式：未定义变量渲染时报错（生产环境建议 false） */
    private boolean strictMode = false;

    /** 默认语言 */
    private String defaultLanguage = "zh-CN";
}
