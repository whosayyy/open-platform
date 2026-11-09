package com.yin.ai.platform.agent.pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 默认 Agent 路由器 — 组合多个 ModeClassifier 形成分类链。
 * <p>
 * 决策优先级：
 * 1. 用户主动选择（前端深度模式开关）→ DEEP_RESEARCH
 * 2. 规则分类器链（按 priority 排序）
 * 3. LLM 分类器兜底（如果注册了的话）
 * 4. 最终 fallback → REACT
 */
public class DefaultAgentRouter implements AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRouter.class);

    private final List<ModeClassifier> classifiers;

    public DefaultAgentRouter(List<ModeClassifier> classifiers) {
        this.classifiers = classifiers.stream()
                .sorted(Comparator.comparingInt(ModeClassifier::priority))
                .toList();
        log.info("[ROUTER] initialized with {} classifiers: {}",
                classifiers.size(),
                classifiers.stream().map(ModeClassifier::name).toList());
    }

    @Override
    public AgentMode route(String userInput, RouteContext ctx) {
        // 0. 强制模式（Service 层通过 hints 显式指定，优先级最高）
        if (ctx.hints() != null && ctx.hints().containsKey("forcedMode")) {
            String forced = String.valueOf(ctx.hints().get("forcedMode"));
            try {
                AgentMode mode = AgentMode.valueOf(forced);
                log.info("[ROUTER] forcedMode hint → {}", mode);
                return mode;
            } catch (IllegalArgumentException e) {
                log.warn("[ROUTER] invalid forcedMode hint: {}", forced);
            }
        }

        // 1. 用户主动选择优先
        if (ctx.userRequestedDeepMode()) {
            log.info("[ROUTER] user requested deep mode → DEEP_RESEARCH");
            return AgentMode.DEEP_RESEARCH;
        }

        // 2. 分类器链
        for (ModeClassifier classifier : classifiers) {
            Optional<AgentMode> result = classifier.classify(userInput, ctx);
            if (result.isPresent()) {
                log.info("[ROUTER] classifier={} → {}", classifier.name(), result.get());
                return result.get();
            }
        }

        // 3. 默认 REACT
        log.info("[ROUTER] no classifier matched, default → REACT");
        return AgentMode.REACT;
    }
}
