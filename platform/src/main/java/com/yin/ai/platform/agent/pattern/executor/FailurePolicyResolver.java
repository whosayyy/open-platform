package com.yin.ai.platform.agent.pattern.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 失败策略解析器 — 根据 executor 标识解析对应的失败处理策略。
 * <p>
 * 解析优先级：精确匹配 > 前缀匹配 > 默认（RETRY 2 次）
 */
public class FailurePolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(FailurePolicyResolver.class);
    private static final FailurePolicyConfig DEFAULT = FailurePolicyConfig.retry(2, 1000);

    private final Map<String, FailurePolicyConfig> policies;

    public FailurePolicyResolver() {
        this(FailurePolicyConfig.defaults());
    }

    public FailurePolicyResolver(Map<String, FailurePolicyConfig> policies) {
        this.policies = policies;
    }

    /**
     * 解析 executor 对应的失败策略。
     */
    public FailurePolicyConfig resolve(String executorId) {
        if (executorId == null) return DEFAULT;

        // 精确匹配
        FailurePolicyConfig exact = policies.get(executorId);
        if (exact != null) return exact;

        // 前缀匹配（如 "skill:news-gen" 匹配 "skill"）
        String prefix = executorId.contains(":")
                ? executorId.substring(0, executorId.indexOf(":"))
                : executorId;
        FailurePolicyConfig prefixMatch = policies.get(prefix);
        if (prefixMatch != null) return prefixMatch;

        return DEFAULT;
    }

    /**
     * 计算第 N 次重试的延迟（指数退避）。
     */
    public long computeRetryDelay(FailurePolicyConfig config, int attempt) {
        if (!config.exponentialBackoff()) return config.retryDelayMs();
        return config.retryDelayMs() * (long) Math.pow(2, attempt);
    }
}
