package com.yin.ai.platform.gateway;

/**
 * AI Gateway 统一消息模型 — 与具体 Provider 的消息类型解耦。
 * <p>
 * Adapter 层通过 {@link com.yin.ai.platform.gateway.adapter.MessageConverter}
 * 将此类转换为对应 Provider 的消息类型（如 Spring AI Message）。
 */
public class GatewayMessage {

    public enum Role {
        system, user, assistant, tool
    }

    private Role role;
    private String content;
    private String name;
    private String toolCallId;
    /** 是否标记为可缓存（DeepSeek/Anthropic cache_control） */
    private boolean cacheControl;

    public GatewayMessage() { }

    public GatewayMessage(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    public GatewayMessage(Role role, String content, String name, String toolCallId) {
        this.role = role;
        this.content = content;
        this.name = name;
        this.toolCallId = toolCallId;
    }

    public static GatewayMessage system(String content) {
        return new GatewayMessage(Role.system, content);
    }

    public static GatewayMessage system(String content, boolean cacheControl) {
        GatewayMessage msg = new GatewayMessage(Role.system, content);
        msg.cacheControl = cacheControl;
        return msg;
    }

    public static GatewayMessage user(String content) {
        return new GatewayMessage(Role.user, content);
    }

    public static GatewayMessage assistant(String content) {
        return new GatewayMessage(Role.assistant, content);
    }

    public static GatewayMessage tool(String content, String name, String toolCallId) {
        return new GatewayMessage(Role.tool, content, name, toolCallId);
    }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public boolean isCacheControl() { return cacheControl; }
    public void setCacheControl(boolean cacheControl) { this.cacheControl = cacheControl; }
}
