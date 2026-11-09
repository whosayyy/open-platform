package com.yin.ai.platform.core.router;

import java.util.Optional;
import java.util.Set;

/**
 * 意图匹配器 — SPI 接口。
 * <p>
 * 每个实现负责判断用户输入是否匹配特定意图。
 * 必须是纯函数：无副作用，不调用外部服务（HTTP/DB/LLM），5ms 内完成。
 * <p>
 * 优先级内置范围：100(Keyword) > 80(Regex) > 60(Rule) > 40(Embedding，V2.1)
 */
@FunctionalInterface
public interface IntentMatcher {

    /** 匹配器优先级，值越大越优先匹配。 */
    default int priority() {
        return 0;
    }

    /** 匹配器名称（用于日志和调试）。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** 支持的所有意图（一个 Matcher 可以支持多个意图标识符）。 */
    default Set<String> supportedIntents() {
        return Set.of();
    }

    /**
     * 尝试匹配用户输入。
     *
     * @param userInput 用户原始输入
     * @param ctx       路由上下文
     * @return 匹配结果，如果不匹配返回 Optional.empty()
     */
    Optional<IntentMatchResult> match(String userInput, RouteContext ctx);
}
