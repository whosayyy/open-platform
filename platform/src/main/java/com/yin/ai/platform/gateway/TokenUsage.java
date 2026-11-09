package com.yin.ai.platform.gateway;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Token 用量模型。
 */
public class TokenUsage {
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    /** 缓存命中的 prompt tokens（仅计费 10%） */
    private Integer cachedPromptTokens;
    /** 写入缓存的 tokens（首次写入计费 110%） */
    private Integer cacheWriteTokens;
    /** 本次请求是否命中 Prompt Cache */
    private boolean cacheHit;

    public TokenUsage() {}

    public TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Integer getCachedPromptTokens() { return cachedPromptTokens; }
    public void setCachedPromptTokens(Integer cachedPromptTokens) { this.cachedPromptTokens = cachedPromptTokens; }

    public Integer getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Integer cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }

    public boolean isCacheHit() { return cacheHit; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }

    /** 实际计费 prompt tokens（缓存命中 10% 计费，未命中 100% 计费） */
    @JsonIgnore
    public int getBillablePromptTokens() {
        if (cachedPromptTokens != null && cachedPromptTokens > 0) {
            int nonCached = promptTokens != null ? promptTokens - cachedPromptTokens : 0;
            int cachedBillable = (int) Math.ceil(cachedPromptTokens * 0.1);
            return nonCached + cachedBillable;
        }
        return promptTokens != null ? promptTokens : 0;
    }
}
