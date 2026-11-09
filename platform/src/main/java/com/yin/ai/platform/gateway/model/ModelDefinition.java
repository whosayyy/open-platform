package com.yin.ai.platform.gateway.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型实例定义 — 描述一个可用的 AI 模型实例的完整配置。
 * <p>
 * 每个 ModelDefinition 对应一个独立的模型端点（如 deepseek-chat、qwen-plus），
 * 包含其 provider、endpoint、认证、计费、速率限制等全部元数据。
 */
public class ModelDefinition {

    /** 模型唯一标识（如 "deepseek-chat"、"qwen-plus"） */
    private final String name;
    /** Provider 名称（如 "deepseek"、"dashscope"、"openai"），对应 ModelAdapter.name() */
    private final String provider;
    /** API Base URL */
    private final String baseUrl;
    /** API Key */
    private final String apiKey;
    /** 模型最大 Token 数 */
    private final int maxTokens;
    /** prompt token 单价（USD/1K tokens） */
    private final double costPer1kPromptTokens;
    /** completion token 单价（USD/1K tokens） */
    private final double costPer1kCompletionTokens;
    /** 是否启用 */
    private final boolean enabled;
    /** 每分钟请求速率限制（0 = 不限） */
    private final int rateLimitPerMinute;
    /** 扩展元数据 */
    private final Map<String, Object> metadata;
    private final ModelCapabilities capabilities;

    private ModelDefinition(Builder builder) {
        this.name = builder.name;
        this.provider = builder.provider;
        this.baseUrl = builder.baseUrl;
        this.apiKey = builder.apiKey;
        this.maxTokens = builder.maxTokens;
        this.costPer1kPromptTokens = builder.costPer1kPromptTokens;
        this.costPer1kCompletionTokens = builder.costPer1kCompletionTokens;
        this.enabled = builder.enabled;
        this.rateLimitPerMinute = builder.rateLimitPerMinute;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.capabilities = builder.capabilities;
    }

    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public int getMaxTokens() { return maxTokens; }
    public double getCostPer1kPromptTokens() { return costPer1kPromptTokens; }
    public double getCostPer1kCompletionTokens() { return costPer1kCompletionTokens; }
    public boolean isEnabled() { return enabled; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public Map<String, Object> getMetadata() { return metadata; }
    public ModelCapabilities getCapabilities() { return capabilities; }

    /**
     * 计算预估成本（USD）。
     */
    public double estimateCost(int promptTokens, int completionTokens) {
        return (promptTokens / 1000.0) * costPer1kPromptTokens
                + (completionTokens / 1000.0) * costPer1kCompletionTokens;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String provider;
        private String baseUrl;
        private String apiKey;
        private int maxTokens = 4096;
        private double costPer1kPromptTokens;
        private double costPer1kCompletionTokens;
        private boolean enabled = true;
        private int rateLimitPerMinute;
        private final Map<String, Object> metadata = new HashMap<>();
        private ModelCapabilities capabilities = ModelCapabilities.standard();

        public Builder name(String name) { this.name = name; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder costPer1kPromptTokens(double cost) { this.costPer1kPromptTokens = cost; return this; }
        public Builder costPer1kCompletionTokens(double cost) { this.costPer1kCompletionTokens = cost; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder rateLimitPerMinute(int rpm) { this.rateLimitPerMinute = rpm; return this; }
        public Builder metadata(String key, Object value) { this.metadata.put(key, value); return this; }
        public Builder capabilities(ModelCapabilities capabilities) {
            this.capabilities = capabilities == null ? ModelCapabilities.standard() : capabilities;
            return this;
        }

        public ModelDefinition build() {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
            if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider is required");
            return new ModelDefinition(this);
        }
    }

    @Override
    public String toString() {
        return "ModelDefinition{name='" + name + "', provider='" + provider
                + "', enabled=" + enabled + ", maxTokens=" + maxTokens + "}";
    }
}
