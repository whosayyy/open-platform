package com.yin.ai.platform.gateway;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI Gateway 请求 — 封装一次 LLM 调用的所有参数。
 */
public class GatewayRequest {
    /** 消息列表 (GatewayMessage，与具体 Provider 解耦) */
    private List<GatewayMessage> messages;
    /** 可选的 System Prompt（优先级高于 messages 中的 system 角色） */
    private String systemPrompt;
    /** 允许的函数名列表（空 = 无 Function Calling） */
    private Set<String> functionNames;
    /** 调用选项 */
    private GatewayOptions options;
    /** 链路追踪 ID */
    private String traceId;
    /** 调用方标识（{skillName} 或 {serviceName}） */
    private String caller;
    /** 使用的 Prompt 模板名称（可选，用于 PromptInjectionFilter） */
    private String promptTemplate;
    /** Prompt 变量（可选） */
    private Map<String, Object> promptVariables;
    /** 租户 ID（用于多租户隔离，由 TenantContextFilter 注入） */
    private String tenantId;
    /** Authenticated user ID. Kept separate from tenantId during compatibility fallback. */
    private String userId;
    /** Stable conversation/session identifier used by policy and audit layers. */
    private String sessionId;
    /** Authenticated roles. */
    private Set<String> roles;
    /** Authenticated permissions. */
    private Set<String> permissions;
    /** Selected immutable Prompt version and experiment assignment (never rendered content). */
    private String promptVersion;
    private String experimentId;
    private String variantId;

    public GatewayRequest() { }

    public GatewayRequest(List<GatewayMessage> messages, GatewayOptions options) {
        this.messages = messages;
        this.options = options;
    }

    public List<GatewayMessage> getMessages() { return messages; }
    public void setMessages(List<GatewayMessage> messages) { this.messages = messages; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public Set<String> getFunctionNames() { return functionNames; }
    public void setFunctionNames(Set<String> functionNames) { this.functionNames = functionNames; }

    public GatewayOptions getOptions() { return options; }
    public void setOptions(GatewayOptions options) { this.options = options; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getCaller() { return caller; }
    public void setCaller(String caller) { this.caller = caller; }

    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }

    public Map<String, Object> getPromptVariables() { return promptVariables; }
    public void setPromptVariables(Map<String, Object> promptVariables) { this.promptVariables = promptVariables; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }

    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) { this.permissions = permissions; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }

    public String getVariantId() { return variantId; }
    public void setVariantId(String variantId) { this.variantId = variantId; }
}
