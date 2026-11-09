package com.yin.ai.platform.core.skill.selector;

import com.yin.ai.platform.core.router.RouteContext;

/**
 * Skill Selector — 根据 Intent 和 RouteContext 选择需要哪些 Skill（SPI）。
 * <p>
 * 替代旧架构中 IntentHandler 的硬编码 Intent → 单 Skill 映射。
 * 支持多 Skill 选择：一个 Intent 可映射到 N 个 Skill。
 * <p>
 * <b>V3.0 预留实现：</b>
 * <ul>
 *   <li>{@code EmbeddingSkillSelector} — 将 userInput 向量化，余弦匹配 Skill 描述</li>
 *   <li>{@code LLMSkillSelector} — 轻量 LLM 分类选择 Skill 集</li>
 * </ul>
 */
public interface SkillSelector {

    /**
     * 根据路由上下文和意图选择 Skill 列表。
     *
     * @param context 路由上下文（sessionId, userId, metadata）
     * @param intent  识别到的用户意图（业务定义的 String 标识符）
     * @return 选中的 Skill 名称列表（按优先级排序）
     */
    SkillSelectionResult select(RouteContext context, String intent);

    /**
     * 选择器名称，用于日志和 Metrics 标记。
     */
    String selectorName();
}
