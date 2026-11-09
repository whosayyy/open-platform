package com.yin.ai.platform.core.router.matcher;

import com.yin.ai.platform.core.router.IntentMatchResult;
import com.yin.ai.platform.core.router.IntentMatcher;
import com.yin.ai.platform.core.router.RouteContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 规则匹配器 — 优先级 60。
 * <p>
 * 基于上下文字段匹配，而非纯文本。规则由调用方通过构造器提供。
 * <p>
 * 配置示例：
 * <pre>
 * ai.router.metadata-rules:
 *   - metadata-key: "resourceId"
 *     condition-key: "action"
 *     condition-value: "process"
 *     intent: "workflow"
 * </pre>
 */
public class RuleMatcher implements IntentMatcher {

    private final List<MetadataRoutingRule> rules;

    public RuleMatcher(List<MetadataRoutingRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public String name() {
        return "rule";
    }

    @Override
    public Set<String> supportedIntents() {
        return rules.stream()
                .map(MetadataRoutingRule::intent)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public Optional<IntentMatchResult> match(String userInput, RouteContext ctx) {
        if (ctx == null || ctx.getMetadata() == null || ctx.getMetadata().isEmpty()
                || rules.isEmpty()) {
            return Optional.empty();
        }

        for (MetadataRoutingRule rule : rules) {
            if (ctx.getMetadata().containsKey(rule.metadataKey())) {
                String actualValue = Objects.toString(
                        ctx.getMetadata().get(rule.conditionKey()), "");

                if (rule.conditionValue().equalsIgnoreCase(actualValue)) {
                    return Optional.of(IntentMatchResult.builder()
                            .intent(rule.intent())
                            .confidence(0.95)
                            .matchedBy("rule")
                            .matchedRule("metadata." + rule.conditionKey()
                                    + "=" + rule.conditionValue()
                                    + " with " + rule.metadataKey())
                            .build());
                }
            }
        }

        return Optional.empty();
    }
}
