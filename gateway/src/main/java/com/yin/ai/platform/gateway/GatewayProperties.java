package com.yin.ai.platform.gateway;

import com.yin.ai.platform.gateway.config.CostConfig;
import com.yin.ai.platform.gateway.config.FilterConfig;
import com.yin.ai.platform.gateway.config.HealthConfig;
import com.yin.ai.platform.gateway.config.PromptCacheConfig;
import com.yin.ai.platform.gateway.config.RateLimitConfig;
import com.yin.ai.platform.gateway.config.RouterConfig;
import com.yin.ai.platform.gateway.config.SemanticCacheConfig;
import com.yin.ai.platform.gateway.config.TokenBudgetConfig;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "ai.gateway")
public class GatewayProperties {

    private String defaultModel = "deepseek-chat";
    private double defaultTemperature = 0.7;
    private int defaultMaxTokens = 4096;
    private long globalTimeoutMs = 120_000;
    private long streamTimeoutMs = 300_000;
    private long firstTokenTimeoutMs = 30_000;
    private int maxRetries = 2;
    private long retryBackoffMs = 1000;
    private boolean fallbackEnabled = true;
    private int sinkBufferSize = 256;
    private boolean auditLogEnabled = true;
    private String auditLogStore = "database";
    private boolean metricsEnabled = true;
    private boolean costTrackingEnabled = true;

    private Map<String, ModelConfig> models = new HashMap<>();

    @NestedConfigurationProperty
    private FilterConfig filters = new FilterConfig();

    @NestedConfigurationProperty
    private PromptCacheConfig promptCache = new PromptCacheConfig();

    @NestedConfigurationProperty
    private SemanticCacheConfig semanticCache = new SemanticCacheConfig();

    @NestedConfigurationProperty
    private TokenBudgetConfig tokenBudget = new TokenBudgetConfig();

    @NestedConfigurationProperty
    private RouterConfig modelRouter = new RouterConfig();

    @NestedConfigurationProperty
    private CostConfig cost = new CostConfig();

    @NestedConfigurationProperty
    private HealthConfig health = new HealthConfig();

    @NestedConfigurationProperty
    private RateLimitConfig rateLimit = new RateLimitConfig();

    @NestedConfigurationProperty
    private ThinkingConfig thinking = new ThinkingConfig();

    @Data
    public static class ModelConfig {
        private String provider;
        private String baseUrl;
        @ToString.Exclude
        private String apiKey;
        private int maxTokens = 4096;
        private double costPer1kPromptTokens;
        private double costPer1kCompletionTokens;
        private boolean enabled = true;
        private int rateLimitPerMinute;
    }

    @Data
    public static class ThinkingConfig {
        /** 是否启用 DeepSeek 思考模式（发送 thinking: {"type":"enabled"}） */
        private boolean enabled = false;
        /** 思考力度：low / medium / high，映射 reasoning_effort */
        private String effort = "high";
    }
}
