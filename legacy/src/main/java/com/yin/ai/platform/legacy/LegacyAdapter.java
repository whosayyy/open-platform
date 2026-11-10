package com.yin.ai.platform.legacy;

import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.core.router.IntentRouter;
import com.yin.ai.platform.core.router.RouteContext;
import com.yin.ai.platform.core.router.RouteResult;
import com.yin.ai.platform.runtime.SkillExecutionContext;
import com.yin.ai.platform.runtime.SkillResult;
import com.yin.ai.platform.runtime.SkillRuntime;
import com.yin.ai.platform.core.skill.resolver.DependencyResolver;
import com.yin.ai.platform.core.skill.selector.SkillSelectionResult;
import com.yin.ai.platform.core.skill.selector.SkillSelector;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V1 → V2 适配器。
 * <p>
 * 将 V1 Service 类的调用委托到 V2 完整链路：
 * <ol>
 *   <li>IntentRouter.route() → 识别 Intent</li>
 *   <li>SkillSelector.select() → 选择 Skill 列表</li>
 *   <li>DependencyResolver.resolve() → 递归补齐依赖</li>
 *   <li>SkillRuntime.execute(List) → 加载 + 组合 + 调用 LLM</li>
 * </ol>
 * V1 端点保持不变，内部通过此适配器桥接到 V2 架构。
 *
 * <pre>
 * 使用方式：
 *   SkillResult result = legacyAdapter.routeAndExecute(userInput, sessionId, userId);
 *   String responseText = (String) result.getData();
 * </pre>
 */
@Slf4j
public class LegacyAdapter {

    private final IntentRouter intentRouter;
    private final SkillRuntime skillRuntime;
    private final SkillSelector skillSelector;
    private final DependencyResolver dependencyResolver;

    public LegacyAdapter(IntentRouter intentRouter,
                         SkillRuntime skillRuntime,
                         SkillSelector skillSelector,
                         DependencyResolver dependencyResolver) {
        this.intentRouter = intentRouter;
        this.skillRuntime = skillRuntime;
        this.skillSelector = skillSelector;
        this.dependencyResolver = dependencyResolver;
    }

    /**
     * 路由并执行（阻塞式）。
     * <p>
     * 完整链路：Route → Select → Resolve → Execute
     *
     * @param userInput 用户输入
     * @param sessionId 会话 ID（可选）
     * @param userId    用户 ID（可选）
     * @return Skill 执行结果
     */
    public SkillResult routeAndExecute(String userInput, String sessionId, Long userId) {
        // 1. Route — 识别 Intent
        RouteContext routeCtx = RouteContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .traceId(java.util.UUID.randomUUID().toString().substring(0, 8))
                .build();

        RouteResult routeResult = intentRouter.route(userInput, routeCtx);
        log.info("[LEGACY] intent={} input=\"{}\"", routeResult.getIntent(), truncate(userInput, 80));

        // 2. Select — 选择 Skill 列表
        SkillSelectionResult selection = skillSelector.select(routeCtx, routeResult.getIntent());
        log.info("[LEGACY] selected skills={} by={}", selection.getSkillNames(), selection.getSelectorName());

        // 3. Resolve — 递归补齐依赖
        List<String> resolvedNames = dependencyResolver.resolve(selection.getSkillNames());
        log.info("[LEGACY] resolved skills={}", resolvedNames);

        // 4. Execute — 多 Skill 执行
        SkillExecutionContext execCtx = SkillExecutionContext.builder()
                .traceId(routeCtx.getTraceId())
                .userInput(userInput)
                .sessionId(sessionId)
                .userId(userId)
                .build();

        return skillRuntime.execute(resolvedNames, execCtx);
    }

    /**
     * 路由并执行（流式）。
     * <p>
     * 完整链路：Route → Select → Resolve → ExecuteStream
     *
     * @param userInput 用户输入
     * @param sessionId 会话 ID（可选）
     * @param userId    用户 ID（可选）
     * @return SSE 事件流
     */
    public Flux<StreamPacket> routeAndExecuteStream(String userInput, String sessionId, Long userId) {
        return routeAndExecuteStream(userInput, sessionId, userId,
                UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 路由并执行（流式），指定 traceId（用于外部取消控制）。
     */
    public Flux<StreamPacket> routeAndExecuteStream(String userInput, String sessionId, Long userId,
                                                     String traceId) {
        // 1. Route
        RouteContext routeCtx = RouteContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .traceId(traceId)
                .build();

        RouteResult routeResult = intentRouter.route(userInput, routeCtx);
        log.info("[LEGACY-STREAM] intent={} input=\"{}\"", routeResult.getIntent(), truncate(userInput, 80));

        // 2. Select
        SkillSelectionResult selection = skillSelector.select(routeCtx, routeResult.getIntent());
        log.info("[LEGACY-STREAM] selected skills={} by={}", selection.getSkillNames(), selection.getSelectorName());

        // 3. Resolve
        List<String> resolvedNames = dependencyResolver.resolve(selection.getSkillNames());
        log.info("[LEGACY-STREAM] resolved skills={}", resolvedNames);

        // 4. ExecuteStream
        SkillExecutionContext execCtx = SkillExecutionContext.builder()
                .traceId(traceId)
                .userInput(userInput)
                .sessionId(sessionId)
                .userId(userId)
                .build();

        return skillRuntime.executeStream(resolvedNames, execCtx);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
