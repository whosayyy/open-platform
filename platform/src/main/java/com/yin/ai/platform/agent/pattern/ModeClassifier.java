package com.yin.ai.platform.agent.pattern;

import java.util.Optional;

/**
 * 模式分类器 SPI — AgentRouter 内部的分类链节点。
 * <p>
 * 多个 ModeClassifier 按 priority() 排序组成链，
 * 第一个返回非空结果的分类器"赢得"路由决策。
 * 全部返回 empty 时由 LlmModeClassifier 兜底。
 */
public interface ModeClassifier {

    /**
     * 尝试对用户输入进行分类。
     *
     * @param userInput 用户原始输入
     * @param ctx       路由上下文
     * @return 分类结果，empty 表示本分类器无法判断
     */
    Optional<AgentMode> classify(String userInput, RouteContext ctx);

    /** 优先级，数值越小越先执行 */
    int priority();

    /** 分类器名称（日志/监控用） */
    default String name() {
        return getClass().getSimpleName();
    }
}
