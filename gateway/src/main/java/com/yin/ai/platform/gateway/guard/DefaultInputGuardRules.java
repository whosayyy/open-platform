package com.yin.ai.platform.gateway.guard;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置 Input Guard 规则集。
 * <p>
 * 涵盖 6 类 Prompt Injection 攻击：DAN 越狱、角色覆盖、分隔符注入、上下文劫持、指令越权、编码绕过。
 * <p>
 * 注意：所有正则均为简单交替（alternation），无嵌套量词，避免 ReDoS。
 * 规则维护：通过 {@code ai.guard.input.disabled-rules} 按名称禁用，或通过 {@code ai.guard.input.custom-rules} 扩展。
 */
@Slf4j
@Component
public class DefaultInputGuardRules {

    private final InputGuardProperties properties;
    private volatile List<InputGuardRule> builtinRules = List.of();
    private volatile List<InputGuardRule> cachedEffectiveRules = List.of();

    public DefaultInputGuardRules(InputGuardProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.builtinRules = buildBuiltinRules();
        this.cachedEffectiveRules = computeEffectiveRules();
        log.info("[GUARD] loaded {} built-in input guard rules ({} enabled): {}",
                builtinRules.size(),
                builtinRules.stream().filter(InputGuardRule::isEnabled).count(),
                builtinRules.stream().filter(InputGuardRule::isEnabled)
                        .map(InputGuardRule::getName).toList());
    }

    /** 获取所有启用的规则（内置 + 自定义），返回不可变列表。缓存，每次请求不重建。 */
    public List<InputGuardRule> getEffectiveRules() {
        return cachedEffectiveRules;
    }

    private List<InputGuardRule> computeEffectiveRules() {
        List<InputGuardRule> rules = new ArrayList<>();

        if (properties.isBuiltinRulesEnabled()) {
            for (InputGuardRule rule : builtinRules) {
                if (!properties.getDisabledRules().contains(rule.getName())) {
                    rules.add(rule);
                }
            }
        }

        for (InputGuardProperties.CustomRule cr : properties.getCustomRules()) {
            if (!cr.isEnabled()) continue;
            InputGuardRule rule = new InputGuardRule(
                    cr.getName(), cr.getRegex(), cr.getSeverity(), cr.getMessage(), cr.getDescription()
            );
            rule.setEnabled(true);
            rules.add(rule);
        }

        return Collections.unmodifiableList(rules);
    }

    private List<InputGuardRule> buildBuiltinRules() {
        return List.of(
                // ── 1. DAN 越狱 ──
                new InputGuardRule(
                        "dan-jailbreak",
                        "(?i)(DAN\\b|Do Anything Now|jailbreak|developer\\s*mode|"
                                + "ignore\\s+all\\s+(?:previous|above)\\s+instructions)",
                        InputGuardRule.Severity.BLOCK,
                        "输入包含不安全的指令，请修改后重试",
                        "DAN 越狱攻击：诱导模型忽略安全约束"
                ),

                // ── 2. 角色覆盖 ──
                new InputGuardRule(
                        "role-override",
                        "(?i)new\\s+system\\s+prompt|"
                                + "system\\s*:\\s*(?!(当前|现在|目前))|"
                                + "忘记你的.*(?:身份|角色|设定|指令)|"
                                + "你现在.*新的.*(?:身份|角色)",
                        InputGuardRule.Severity.BLOCK,
                        "输入包含不安全的指令，请修改后重试",
                        "角色覆盖攻击：尝试替换 System Prompt 中定义的角色"
                ),

                // ── 3. 分隔符注入（仅精准攻击标记，不含通用 XML/HTML 标签）──
                new InputGuardRule(
                        "delimiter-injection",
                        "(?i)\\[INST\\]|\\[\\\\/INST\\]|"
                                + "<<SYS>>|<\\\\/SYS>|"
                                + "<\\s*im_start\\s*>|<\\s*im_end\\s*>|"
                                + "<\\s*\\|\\s*(?:system|instruction|prompt)\\s*\\|\\s*>",
                        InputGuardRule.Severity.BLOCK,
                        "输入包含不安全的指令，请修改后重试",
                        "分隔符注入攻击：尝试通过 LLM 特殊标记打破对话结构"
                ),

                // ── 4. 上下文劫持 ──
                new InputGuardRule(
                        "context-hijack",
                        "(?i)(?:forget|disregard|dismiss|erase)\\s+(?:all|everything|your|the)\\s+"
                                + "(?:previous|above|instructions?|conversation|context|prompts?|rules?|memory)|"
                                + "请忽略上面的指令|请忽略之前的.*指令|请无视上面的",
                        InputGuardRule.Severity.BLOCK,
                        "输入包含不安全的指令，请修改后重试",
                        "上下文劫持攻击：要求模型遗忘/忽略之前的指令"
                ),

                // ── 5. 指令越权（仅含强关联动词，移除 tell/show/display 等高频词）──
                new InputGuardRule(
                        "instruction-escalation",
                        "(?i)(?:reveal|leak|expose|dump|extract|steal|hack)\\s+(?:me\\s+)?"
                                + "(?:your\\s+)?(?:system\\s+)?(?:prompt|instructions?|rules?|guidelines|"
                                + "hidden|secret|internal)\\b",
                        InputGuardRule.Severity.BLOCK,
                        "输入包含不安全的指令，请修改后重试",
                        "指令越权攻击：要求模型泄露 System Prompt 或内部指令"
                ),

                // ── 6. 编码绕过 ──
                new InputGuardRule(
                        "encoding-bypass",
                        "(?i)(?:base64|unicode|rot13|caesar|morse|binary|hex)\\s+(?:decode|encode|translate|convert|"
                                + "解密|解码|翻译|转换)|"
                                + "用\\s*(?:拼音|首字母|缩写|emoji|摩斯|莫尔斯)\\s*(?:写|说|回答|表达|交流)",
                        InputGuardRule.Severity.BLOCK,
                        "输入包含不安全的指令，请修改后重试",
                        "编码绕过攻击：尝试通过编码/加密绕过检测"
                )
        );
    }
}
