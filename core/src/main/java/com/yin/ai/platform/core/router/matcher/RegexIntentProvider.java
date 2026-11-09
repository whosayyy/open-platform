package com.yin.ai.platform.core.router.matcher;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 正则意图匹配 模式提供者 SPI。
 * <p>
 * Adapter/Starter 负责把外部配置编译为正则条目。
 */
public interface RegexIntentProvider {

    record RegexIntentEntry(Set<String> preFilterKeywords, List<Pattern> patterns) {}

    /**
     * 返回 intent → 正则条目 的映射。空 Map 时 RegexMatcher 为无操作。
     */
    Map<String, RegexIntentEntry> getPatterns();

}
