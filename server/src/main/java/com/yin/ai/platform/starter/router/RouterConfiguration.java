package com.yin.ai.platform.starter.router;

import com.yin.ai.platform.adapter.router.cache.IntentCache;
import com.yin.ai.platform.adapter.router.embedding.EmbeddingMatcher;
import com.yin.ai.platform.adapter.router.observability.AiMetricsRouterTelemetry;
import com.yin.ai.platform.core.router.DefaultIntentRouter;
import com.yin.ai.platform.core.router.IntentMatcher;
import com.yin.ai.platform.core.router.IntentRouter;
import com.yin.ai.platform.core.router.RouterTelemetry;
import com.yin.ai.platform.core.router.matcher.DefaultMatcher;
import com.yin.ai.platform.core.router.matcher.KeywordIntentProvider;
import com.yin.ai.platform.core.router.matcher.KeywordMatcher;
import com.yin.ai.platform.core.router.matcher.MetadataRoutingRule;
import com.yin.ai.platform.core.router.matcher.RegexIntentProvider;
import com.yin.ai.platform.core.router.matcher.RegexMatcher;
import com.yin.ai.platform.core.router.matcher.RuleMatcher;
import com.yin.ai.platform.core.router.query.QueryNormalizer;
import com.yin.ai.platform.core.router.query.QuerySynonymSource;
import com.yin.ai.platform.engine.metrics.AiMetrics;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Intent Router 模块 Spring 配置。
 */
@Configuration
public class RouterConfiguration {

    // ——— Normalizer & Cache ———

    @Bean
    public QueryNormalizer queryNormalizer(RouterProperties properties,
                                           ObjectProvider<QuerySynonymSource> sources) {
        Map<String, String> mappings = new LinkedHashMap<>(properties.getNormalizerMappings());
        sources.orderedStream().map(QuerySynonymSource::synonyms)
                .filter(java.util.Objects::nonNull).forEach(mappings::putAll);
        return new QueryNormalizer(mappings);
    }

    @Bean
    public IntentCache intentCache(IntentCacheProperties props, RedissonClient redisson) {
        return new IntentCache(props.toOptions(), redisson);
    }

    // ——— Intent Providers ———

    @Bean
    public KeywordIntentProvider keywordIntentProvider(RouterProperties props) {
        return new YamlKeywordIntentProvider(props);
    }

    @Bean
    public RegexIntentProvider regexIntentProvider(RouterProperties props) {
        Map<String, RouterProperties.RegexPatternGroup> config = props.getRegexPatterns();
        if (config == null || config.isEmpty()) {
            return Map::of;
        }
        Map<String, RegexIntentProvider.RegexIntentEntry> patterns = config.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            RouterProperties.RegexPatternGroup group = entry.getValue();
                            Set<String> keywords = group.getPreFilterKeywords() == null
                                    ? Set.of() : Set.copyOf(group.getPreFilterKeywords());
                            List<Pattern> compiled = group.getPatterns() == null
                                    ? List.of()
                                    : group.getPatterns().stream()
                                            .map(value -> Pattern.compile(value, Pattern.CASE_INSENSITIVE))
                                            .toList();
                            return new RegexIntentProvider.RegexIntentEntry(keywords, compiled);
                        }));
        return () -> Map.copyOf(patterns);
    }

    // ——— Matchers ———

    @Bean
    public KeywordMatcher keywordMatcher(KeywordIntentProvider provider) {
        return new KeywordMatcher(provider);
    }

    @Bean
    public RegexMatcher regexMatcher(RegexIntentProvider provider) {
        return new RegexMatcher(provider);
    }

    @Bean
    public RuleMatcher ruleMatcher(RouterProperties props) {
        List<MetadataRoutingRule> rules = props.getMetadataRules() == null
                ? List.of()
                : props.getMetadataRules().stream()
                        .map(rule -> new MetadataRoutingRule(
                                rule.getMetadataKey(), rule.getConditionKey(),
                                rule.getConditionValue(), rule.getIntent()))
                        .toList();
        return new RuleMatcher(rules);
    }

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public EmbeddingMatcher embeddingMatcher(EmbeddingModel embeddingModel, RouterProperties props) {
        return new EmbeddingMatcher(embeddingModel, props.getEmbeddingIntentDescriptions());
    }

    @Bean
    public DefaultMatcher defaultMatcher(RouterProperties props) {
        return new DefaultMatcher(props.getDefaultIntent());
    }

    @Bean
    public RouterTelemetry routerTelemetry(AiMetrics metrics) {
        return new AiMetricsRouterTelemetry(metrics);
    }

    // ——— Router ———

    @Bean
    public IntentRouter intentRouter(List<IntentMatcher> matchers,
                                     QueryNormalizer normalizer,
                                     IntentCache cache,
                                     RouterTelemetry telemetry,
                                     RouterProperties props) {
        return new DefaultIntentRouter(
                matchers, normalizer, cache, telemetry, props.getDefaultIntent());
    }
}
