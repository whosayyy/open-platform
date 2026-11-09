package com.yin.ai.platform.gateway.guard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Input Guard 配置属性 — {@code ai.guard.input.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.guard.input")
public class InputGuardProperties {

    /** 是否启用 Input Guard */
    private boolean enabled = true;

    /** 内置规则是否全部启用 */
    private boolean builtinRulesEnabled = true;

    /** 禁用的内置规则名称集合（按名称屏蔽特定规则，无需重启） */
    private java.util.Set<String> disabledRules = new java.util.HashSet<>();

    /** 额外自定义规则（正则字符串，由 DefaultInputGuardRules 编译为 Pattern） */
    private List<CustomRule> customRules = new ArrayList<>();

    @Data
    public static class CustomRule {
        private String name;
        private String regex;
        private InputGuardRule.Severity severity = InputGuardRule.Severity.BLOCK;
        private String message;
        private String description;
        private boolean enabled = true;
    }
}
