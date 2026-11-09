package com.yin.ai.platform.core.router.matcher;

import com.yin.ai.platform.core.router.IntentMatchResult;
import com.yin.ai.platform.core.router.IntentMatcher;
import com.yin.ai.platform.core.router.RouteContext;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 关键词匹配器 — 最高优先级（100）。
 * <p>
 * 通过 {@link KeywordIntentProvider} SPI 获取关键词→意图映射，
 * 业务模块通过 {@code ai.router.keyword-mappings} 配置提供映射表。
 * <p>
 * <b>性能：</b>使用 {@code String.contains()} 单层遍历，<0.3ms。
 */
public class KeywordMatcher implements IntentMatcher {

    private static final double CONFIDENCE = 0.85;

    private final Map<String, String> keywordMap;

    public KeywordMatcher(KeywordIntentProvider provider) {
        this.keywordMap = provider.getKeywordIntentMappings();
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public Set<String> supportedIntents() {
        return Set.copyOf(keywordMap.values());
    }

    @Override
    public Optional<IntentMatchResult> match(String userInput, RouteContext ctx) {
        if (userInput == null || userInput.isBlank() || keywordMap.isEmpty()) {
            return Optional.empty();
        }

        String input = userInput.toLowerCase();

        for (var entry : keywordMap.entrySet()) {
            if (input.contains(entry.getKey())) {
                return Optional.of(IntentMatchResult.builder()
                        .intent(entry.getValue())
                        .confidence(CONFIDENCE)
                        .matchedBy("keyword")
                        .matchedRule("keyword: " + entry.getKey())
                        .build());
            }
        }
        return Optional.empty();
    }
}
