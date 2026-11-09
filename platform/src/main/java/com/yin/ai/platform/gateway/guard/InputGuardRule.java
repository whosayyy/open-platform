package com.yin.ai.platform.gateway.guard;

import java.util.regex.Pattern;

/**
 * Input Guard 检测规则模型。
 * <p>
 * 每条规则包含一个正则模式、严重度、拦截消息和启停控制。
 * 规则集可通过 YAML 配置扩展（{@code ai.guard.input.rules}）。
 */
public class InputGuardRule {

    /** 规则唯一名称 */
    private String name;

    /** 检测正则模式 */
    private Pattern pattern;

    /** 拦截严重度：BLOCK（直接拒绝）/ WARN（记录日志但放行） */
    private Severity severity;

    /** 拦截时返回给用户的消息 */
    private String message;

    /** 规则描述（用于审计日志） */
    private String description;

    /** 是否启用 */
    private boolean enabled = true;

    public InputGuardRule() {
    }

    public InputGuardRule(String name, String regex, Severity severity, String message, String description) {
        this.name = name;
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        this.severity = severity;
        this.message = message;
        this.description = description;
    }

    public boolean matches(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return pattern.matcher(input).find();
    }

    public enum Severity {
        /** 直接拒绝请求，不调用 LLM */
        BLOCK,
        /** 记录日志和指标，但放行请求 */
        WARN
    }

    // ── Getters / Setters ──

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Pattern getPattern() { return pattern; }
    public void setPattern(Pattern pattern) { this.pattern = pattern; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
