package com.yin.ai.platform.core.router;

import com.yin.ai.platform.core.router.query.QueryNormalizer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 默认意图路由器 — 编排 Normalizer → Cache → MatcherChain → Metrics。
 * <p>
 * <b>处理流程：</b>
 * <ol>
 *   <li>{@link QueryNormalizer} 标准化用户输入</li>
 *   <li>{@link IntentCache} 查询缓存，命中则直接返回</li>
 *   <li>缓存未命中 → 遍历 Matcher 链（priority 降序，短路求值）</li>
 *   <li>匹配成功 → 写入缓存 → 记录 Metrics → 返回</li>
 *   <li>全部未命中 → DefaultMatcher 兜底 CHAT</li>
 * </ol>
 * <p>
 * <b>扩展性：</b>新增 Matcher 只需实现 {@link IntentMatcher} 并通过构造器注册，
 * 符合开闭原则（OCP）。
 */
@Slf4j
public class DefaultIntentRouter implements IntentRouter {

    private final List<IntentMatcher> matchers = new ArrayList<>();
    private final QueryNormalizer normalizer;
    private final IntentRouteCache cache;
    private final RouterTelemetry telemetry;
    private final String defaultIntent;

    public DefaultIntentRouter(List<IntentMatcher> matchers,
                               QueryNormalizer normalizer,
                               IntentRouteCache cache,
                               RouterTelemetry telemetry,
                               String defaultIntent) {
        this.matchers.addAll(matchers);
        this.matchers.sort(Comparator.comparingInt(IntentMatcher::priority).reversed());
        this.normalizer = normalizer;
        this.cache = cache == null ? IntentRouteCache.noop() : cache;
        this.telemetry = telemetry == null ? RouterTelemetry.NOOP : telemetry;
        this.defaultIntent = defaultIntent == null || defaultIntent.isBlank() ? "chat" : defaultIntent;
        log.info("[ROUTER] registered {} matchers: {}",
                this.matchers.size(),
                this.matchers.stream().map(m -> m.name() + "(p=" + m.priority() + ")").toList());
    }

    @Override
    public RouteResult route(String userInput, RouteContext ctx) {
        long startNanos = System.nanoTime();

        // 1. Query 标准化
        String normalized = normalizer.normalize(userInput);

        // 2. 缓存查询
        if (normalized != null && !normalized.isEmpty()) {
            Optional<IntentMatchResult> cached = cache.get(normalized);
            //如果有值
            if (cached.isPresent()) {
                telemetry.recordCacheHit();
                IntentMatchResult matchResult = cached.get();
                telemetry.recordIntentDistribution(matchResult.getIntent());

                long totalNanos = System.nanoTime() - startNanos;
                log.debug("[ROUTE] input=\"{}\" status=CACHE_HIT intent={} nanos={}",
                        truncate(userInput, 80), matchResult.getIntent(), totalNanos);

                return RouteResult.builder()
                        .intent(matchResult.getIntent())
                        .matchResult(matchResult)
                        .isDefault("default".equals(matchResult.getMatchedBy()))
                        .build();
            }
        }
        telemetry.recordCacheMiss();

        // 3. 遍历 Matcher Chain
        for (IntentMatcher matcher : matchers) {
            long matcherStart = System.nanoTime();

            try {
                var result = matcher.match(userInput, ctx);
                long matcherNanos = System.nanoTime() - matcherStart;

                if (result.isPresent()) {
                    IntentMatchResult matchResult = result.get();
                    boolean isDefault = "default".equals(matcher.name());

                    // 记录 Metrics
                    telemetry.recordMatcherHit(matcher.name());
                    telemetry.recordMatcherDuration(matcher.name(), matcherNanos / 1000);
                    telemetry.recordIntentDistribution(matchResult.getIntent());
                    telemetry.recordRouterMatch(matcherNanos / 1000, isDefault);

                    // 缓存结果（非 default 匹配）
                    if (normalized != null && !normalized.isEmpty()) {
                        cache.put(normalized, matchResult);
                    }

                    log.info("[ROUTE] input=\"{}\" intent={} confidence={} matchedBy={} "
                            + "rule=\"{}\" isDefault={} matcherNanos={}",
                            truncate(userInput, 80),
                            matchResult.getIntent(),
                            matchResult.getConfidence(),
                            matchResult.getMatchedBy(),
                            matchResult.getMatchedRule(),
                            isDefault,
                            matcherNanos);

                    return RouteResult.builder()
                            .intent(matchResult.getIntent())
                            .matchResult(matchResult)
                            .isDefault(isDefault)
                            .build();
                }

                log.debug("[ROUTE-MATCHER] matcher={} priority={} result=no_match nanos={}",
                        matcher.name(), matcher.priority(), matcherNanos);

            } catch (Exception e) {
                log.warn("[ROUTE-MATCHER] matcher={} priority={} error={}",
                        matcher.name(), matcher.priority(), e.getMessage());
            }
        }

        // 理论上不会到这里（DefaultMatcher 始终匹配），但做防御性处理
        log.warn("[ROUTE-NOMATCH] input=\"{}\" reason=all_matchers_exhausted",
                truncate(userInput, 80));

        return RouteResult.builder()
                .intent(defaultIntent)
                .matchResult(IntentMatchResult.builder()
                        .intent(defaultIntent)
                        .confidence(0.0)
                        .matchedBy("fallback")
                        .matchedRule("all matchers exhausted (bug)")
                        .build())
                .isDefault(true)
                .build();
    }

    @Override
    public List<IntentMatcher> getMatchers() {
        return List.copyOf(matchers);
    }

    @Override
    public void registerMatcher(IntentMatcher matcher) {
        matchers.add(matcher);
        matchers.sort(Comparator.comparingInt(IntentMatcher::priority).reversed());
        log.info("[ROUTER] registered matcher: name={} priority={}", matcher.name(), matcher.priority());
    }


    // ——— 工具方法 ———

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
