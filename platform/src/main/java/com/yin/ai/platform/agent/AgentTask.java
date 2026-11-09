package com.yin.ai.platform.agent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Agent 任务 — 用户目标 + 初始上下文 + 配置。
 *
 * @param goal       用户目标
 * @param context    初始上下文
 * @param toolFilter 可用工具白名单（null = 全部可用）
 * @param config     运行时配置
 */
public record AgentTask(
        String goal,
        Map<String, Object> context,
        Set<String> toolFilter,
        AgentConfig config) {

    public AgentTask {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        context = context != null ? Collections.unmodifiableMap(context) : Collections.emptyMap();
        config = config != null ? config : AgentConfig.defaults();
    }

    public static AgentTask of(String goal) {
        return new AgentTask(goal, Collections.emptyMap(), null, AgentConfig.defaults());
    }

    public static AgentTask of(String goal, AgentConfig config) {
        return new AgentTask(goal, Collections.emptyMap(), null, config);
    }
}
