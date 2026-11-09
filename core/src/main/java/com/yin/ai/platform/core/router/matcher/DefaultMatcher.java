package com.yin.ai.platform.core.router.matcher;

import com.yin.ai.platform.core.router.IntentMatchResult;
import com.yin.ai.platform.core.router.IntentMatcher;
import com.yin.ai.platform.core.router.RouteContext;

import java.util.Optional;
import java.util.Set;

/**
 * 默认匹配器 — 最低优先级（Integer.MIN_VALUE）。
 * <p>
 * 始终返回配置的默认意图，作为路由的最终兜底。
 * 监控 DefaultMatcher 命中率：如果超过 30%，说明 Keyword/Regex/Rule 覆盖不足。
 */
public class DefaultMatcher implements IntentMatcher {

    private final String defaultIntent;

    public DefaultMatcher(String defaultIntent) {
        this.defaultIntent = defaultIntent == null || defaultIntent.isBlank() ? "chat" : defaultIntent;
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public String name() {
        return "default";
    }

    @Override
    public Set<String> supportedIntents() {
        return Set.of(defaultIntent);
    }

    @Override
    public Optional<IntentMatchResult> match(String userInput, RouteContext ctx) {
        return Optional.of(IntentMatchResult.builder()
                .intent(defaultIntent)
                .confidence(0.5)
                .matchedBy("default")
                .matchedRule("no matcher matched, fallback to " + defaultIntent)
                .build());
    }
}
