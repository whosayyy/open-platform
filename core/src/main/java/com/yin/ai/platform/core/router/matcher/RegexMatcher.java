package com.yin.ai.platform.core.router.matcher;

import com.yin.ai.platform.core.router.IntentMatchResult;
import com.yin.ai.platform.core.router.IntentMatcher;
import com.yin.ai.platform.core.router.RouteContext;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则匹配器 — 优先级 80。
 * <p>
 * 通过 {@link RegexIntentProvider} SPI 获取意图→正则模式的映射，
 * 业务模块通过 {@code ai.router.regex-patterns} 配置提供。
 * 采用 keyword pre-filter 优化，避免对所有 Pattern 全量运行。
 */
public class RegexMatcher implements IntentMatcher {

    private static final double CONFIDENCE = 0.80;

    private final Map<String, RegexIntentProvider.RegexIntentEntry> patternMap;

    public RegexMatcher(RegexIntentProvider provider) {
        this.patternMap = provider.getPatterns();
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public String name() {
        return "regex";
    }

    @Override
    public Set<String> supportedIntents() {
        return patternMap.keySet();
    }

    @Override
    public Optional<IntentMatchResult> match(String userInput, RouteContext ctx) {
        if (userInput == null || userInput.isBlank() || patternMap.isEmpty()) {
            return Optional.empty();
        }

        String lower = userInput.toLowerCase();

        for (var entry : patternMap.entrySet()) {
            String intent = entry.getKey();
            RegexIntentProvider.RegexIntentEntry regexEntry = entry.getValue();

            // Pre-filter: 必须先包含触发关键词
            if (!containsAny(lower, regexEntry.preFilterKeywords())) {
                continue;
            }

            // 正则匹配
            for (Pattern pattern : regexEntry.patterns()) {
                Matcher m = pattern.matcher(userInput);
                if (m.find()) {
                    return Optional.of(IntentMatchResult.builder()
                            .intent(intent)
                            .confidence(CONFIDENCE)
                            .matchedBy("regex")
                            .matchedRule("pattern: " + pattern.pattern())
                            .build());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean containsAny(String input, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }
        for (String kw : keywords) {
            if (input.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
