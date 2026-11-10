package com.yin.ai.platform.gateway.audit;

import java.time.LocalDateTime;

/**
 * 审计日志条目。
 */
public class AuditLogEntry {
    private Long id;
    private String traceId;
    private String caller;
    private String model;
    private String systemPromptHash;
    private String userMessageHash;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private Long ttftMs;
    private String status;  // SUCCESS / FALLBACK / ERROR
    private Integer retryCount;
    private Boolean hasFunctionCalling;
    private String toolNames;
    private String responsePreview;
    private LocalDateTime createdAt;
    private String tenantId;
    private String userId;
    private String skill;
    private String promptVersion;
    private String experimentId;
    private String variantId;

    public AuditLogEntry() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getCaller() { return caller; }
    public void setCaller(String caller) { this.caller = caller; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSystemPromptHash() { return systemPromptHash; }
    public void setSystemPromptHash(String systemPromptHash) { this.systemPromptHash = systemPromptHash; }

    public String getUserMessageHash() { return userMessageHash; }
    public void setUserMessageHash(String userMessageHash) { this.userMessageHash = userMessageHash; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public Long getTtftMs() { return ttftMs; }
    public void setTtftMs(Long ttftMs) { this.ttftMs = ttftMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public Boolean getHasFunctionCalling() { return hasFunctionCalling; }
    public void setHasFunctionCalling(Boolean hasFunctionCalling) { this.hasFunctionCalling = hasFunctionCalling; }

    public String getToolNames() { return toolNames; }
    public void setToolNames(String toolNames) { this.toolNames = toolNames; }

    public String getResponsePreview() { return responsePreview; }
    public void setResponsePreview(String responsePreview) { this.responsePreview = responsePreview; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSkill() { return skill; }
    public void setSkill(String skill) { this.skill = skill; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }
    public String getVariantId() { return variantId; }
    public void setVariantId(String variantId) { this.variantId = variantId; }
}
