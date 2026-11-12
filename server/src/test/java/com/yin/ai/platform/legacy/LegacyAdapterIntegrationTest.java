package com.yin.ai.platform.legacy;

import com.yin.ai.platform.config.PipelineConfig;
import com.yin.ai.platform.adapter.router.cache.IntentCache;
import com.yin.ai.platform.adapter.router.cache.IntentCacheOptions;
import com.yin.ai.platform.adapter.router.observability.AiMetricsRouterTelemetry;
import com.yin.ai.platform.core.router.DefaultIntentRouter;
import com.yin.ai.platform.core.router.IntentMatcher;
import com.yin.ai.platform.core.router.matcher.DefaultMatcher;
import com.yin.ai.platform.core.router.matcher.KeywordIntentProvider;
import com.yin.ai.platform.core.router.matcher.KeywordMatcher;
import com.yin.ai.platform.core.router.matcher.RegexIntentProvider;
import com.yin.ai.platform.core.router.matcher.RegexMatcher;
import com.yin.ai.platform.core.router.matcher.RuleMatcher;
import com.yin.ai.platform.core.router.query.QueryNormalizer;
import com.yin.ai.platform.engine.EngineProperties;
import com.yin.ai.platform.engine.Task;
import com.yin.ai.platform.engine.TaskManager;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.SinkStreamProducer;
import com.yin.ai.platform.engine.stream.StreamMetrics;
import com.yin.ai.platform.engine.stream.StreamPacket;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.prompt.PromptCenter;
import com.yin.ai.platform.starter.router.RouterProperties;
import com.yin.ai.platform.starter.router.YamlKeywordIntentProvider;
import com.yin.ai.platform.runtime.SkillResult;
import com.yin.ai.platform.runtime.SkillRuntime;
import com.yin.ai.platform.runtime.PromptComposer;
import com.yin.ai.platform.adapter.skill.repository.ClasspathSkillRepository;
import com.yin.ai.platform.core.skill.repository.SkillRepository;
import com.yin.ai.platform.core.skill.resolver.DependencyResolver;
import com.yin.ai.platform.core.skill.selector.IntentSkillSelector;
import com.yin.ai.platform.starter.skill.SkillConfigurationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LegacyAdapter 集成测试 — 手动装配全链路组件，Mock 外部依赖（Gateway + Redis）。
 * <p>
 * 验证链路：input → route → select → resolve → execute → output
 */
@DisplayName("LegacyAdapter 端到端集成测试")
class LegacyAdapterIntegrationTest {

    private LegacyAdapter legacyAdapter;
    private UnifiedChatClient mockGateway;

    @BeforeEach
    void setUp() {
        // 1. Repository → Loader
        SkillRepository repository = new ClasspathSkillRepository();
        SkillLoader loader = new com.yin.ai.platform.core.skill.DefaultSkillLoader(repository);

        // 2. Resolver
        DependencyResolver resolver = new DependencyResolver(loader);

        // 3. Selector — 使用默认映射（IntentSkillSelector 内置 DEFAULT_MAPPINGS）
        SkillConfigurationProperties props = new SkillConfigurationProperties();
        IntentSkillSelector selector = new IntentSkillSelector(
                props.getIntentMappings(), props.getDefaultSkills());

        // 4. FunctionRegistry (empty)
        FunctionRegistry functionRegistry = new FunctionRegistry();

        // 5. Mock Redis → SessionMemoryManager → PromptComposer
        RedissonClient mockRedis = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RList mockList = mock(RList.class);
        when(mockList.readAll()).thenReturn(List.of());
        when(mockRedis.getList(anyString())).thenReturn(mockList);
        SessionMemoryManager memory = new SessionMemoryManager(mockRedis);
        PromptCenter mockPromptCenter = mock(PromptCenter.class);
        PromptComposer composer = new PromptComposer(functionRegistry, memory, mockPromptCenter);

        // 6. Mock Gateway
        mockGateway = mock(UnifiedChatClient.class);

        // 7. TaskManager + SinkStreamProducer (real Sink for stream test)
        var mockTaskManager = mock(TaskManager.class);
        when(mockTaskManager.createTask(anyString()))
                .thenAnswer(inv -> new Task(inv.getArgument(0), inv.getArgument(0)));
        var mockPipelineConfig = mock(PipelineConfig.class);
        when(mockPipelineConfig.getSinkBufferSize()).thenReturn(256);
        var mockStreamMetrics = mock(StreamMetrics.class);
        var streamProducer = new SinkStreamProducer(mockPipelineConfig, mockTaskManager, mockStreamMetrics, new EngineProperties());

        // 8. Runtime (knowledgeRegistry=null: knowledge disabled in integration test)
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        SkillRuntime runtime = new com.yin.ai.platform.runtime.DefaultSkillRuntime(
                loader, mockGateway, functionRegistry, memory, om, composer, null, null, null, null,
                streamProducer, mockTaskManager);

        // 8. Router — DefaultIntentRouter + real Matchers
        var normalizer = new QueryNormalizer();
        var cache = new IntentCache(new IntentCacheOptions(true, 100, 60));
        var metrics = new com.yin.ai.platform.engine.metrics.AiMetrics(new SimpleMeterRegistry());

        // RouterProperties — keyword mappings matching test inputs
        RouterProperties routerProps = new RouterProperties();
        routerProps.setDefaultIntent("chat");
        routerProps.setKeywordMappings(Map.of(
                "新闻", "chat",
                "今天", "chat",
                "总结", "summary",
                "摘要", "summary",
                "审核", "review",
                "合规", "review",
                "热点", "hot_news",
                "搜索", "search",
                "什么是", "qa",
                "微快传", "qa"
        ));
        KeywordIntentProvider keywordProvider = new YamlKeywordIntentProvider(routerProps);
        RegexIntentProvider regexProvider = Map::of;

        var matchers = java.util.List.<IntentMatcher>of(
                new KeywordMatcher(keywordProvider),
                new RegexMatcher(regexProvider),
                new RuleMatcher(List.of()),
                new DefaultMatcher(routerProps.getDefaultIntent())
        );
        var router = new DefaultIntentRouter(matchers, normalizer, cache,
                new AiMetricsRouterTelemetry(metrics), routerProps.getDefaultIntent());

        // 9. LegacyAdapter
        legacyAdapter = new LegacyAdapter(router, runtime, selector, resolver);
    }

    // ——— 7 个意图场景 ———

    @Test
    @DisplayName("CHAT — \"今天有什么新闻\" → [news-base, chat]")
    void shouldRouteChatIntent() {
        when(mockGateway.chat(any())).thenReturn(new GatewayResponse("今天的热点新闻有：1. ..."));

        SkillResult result = legacyAdapter.routeAndExecute("今天有什么新闻", "test-session", 1L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNotNull();
    }

    @Test
    @DisplayName("SUMMARY — \"帮我总结这篇文章\" → [summary]")
    void shouldRouteSummaryIntent() {
        when(mockGateway.chat(any())).thenReturn(new GatewayResponse("文章摘要：本文主要讲述了..."));

        SkillResult result = legacyAdapter.routeAndExecute("帮我总结这篇文章", null, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("REVIEW — \"这段评论合规吗\" → [review]")
    void shouldRouteReviewIntent() {
        when(mockGateway.chat(any())).thenReturn(new GatewayResponse("{\"approved\": true}"));

        SkillResult result = legacyAdapter.routeAndExecute("这段评论合规吗", null, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("HOT_NEWS — \"最近热点是什么\" → [news-base, hot-news]")
    void shouldRouteHotNewsIntent() {
        when(mockGateway.chat(any())).thenReturn(new GatewayResponse("近期热点：1. XX事件 2. YY新闻"));

        SkillResult result = legacyAdapter.routeAndExecute("最近热点是什么", null, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("SEARCH — \"搜索关于科技的新闻\" → [search]")
    void shouldRouteSearchIntent() {
        when(mockGateway.chat(any())).thenReturn(new GatewayResponse("搜索结果：找到 3 条科技新闻..."));

        SkillResult result = legacyAdapter.routeAndExecute("搜索关于科技的新闻", null, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("QA — \"什么是微快传\" → [qa]")
    void shouldRouteQaIntent() {
        when(mockGateway.chat(any())).thenReturn(new GatewayResponse("微快传是一个新闻聚合平台..."));

        SkillResult result = legacyAdapter.routeAndExecute("什么是微快传", null, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("未知意图 → CHAT fallback → [news-base, chat]")
    void shouldFallbackToChatForUnknownIntent() {
        when(mockGateway.chat(any())).thenReturn(new GatewayResponse("我只处理新闻平台相关的问题"));

        SkillResult result = legacyAdapter.routeAndExecute("今天天气怎么样", "test-session", 1L);

        assertThat(result.isSuccess()).isTrue();
    }

    // ——— 错误场景 ———

    @Test
    @DisplayName("Gateway 异常时返回 fallback 消息")
    void shouldReturnFallbackOnGatewayError() {
        when(mockGateway.chat(any())).thenThrow(new RuntimeException("LLM timeout"));

        SkillResult result = legacyAdapter.routeAndExecute("今天有什么新闻", null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).asString().contains("暂时不可用");
        assertThat(result.getError()).isNotNull();
    }

    // ——— 流式场景 ———

    @Test
    @DisplayName("CHAT 流式 — 多包拼接验证")
    void shouldRouteAndExecuteStreamForChatIntent() {
        when(mockGateway.chatStream(any())).thenReturn(Flux.just(
                StreamPacket.builder().type("text").content("今天的热点新闻").build(),
                StreamPacket.builder().type("text").content("有：1. XX事件").build(),
                StreamPacket.builder().type("text").content(" 2. YY新闻").build(),
                StreamPacket.builder().type("complete").build()
        ));

        List<StreamPacket> packets = legacyAdapter
                .routeAndExecuteStream("今天有什么新闻", "test-session", 1L)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(packets).isNotNull().isNotEmpty();
        String fullText = packets.stream()
                .filter(p -> "text".equals(p.getType()))
                .map(p -> (String) p.getContent())
                .reduce("", String::concat);
        assertThat(fullText).contains("热点新闻");
    }
}
