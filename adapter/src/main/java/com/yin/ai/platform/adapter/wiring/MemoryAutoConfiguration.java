package com.yin.ai.platform.adapter.wiring;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.adapter.ModelAdapter;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.memory.api.impl.DefaultMemoryRuntime;
import com.yin.ai.platform.memory.cache.TieredMemoryCacheManager;
import com.yin.ai.platform.memory.compression.MemoryCompressor;
import com.yin.ai.platform.memory.compression.SummarizationCompressor;
import com.yin.ai.platform.memory.context.DefaultMemoryContextProvider;
import com.yin.ai.platform.memory.context.MemoryContextProvider;
import com.yin.ai.platform.memory.consolidation.LlmFactExtractionConsolidator;
import com.yin.ai.platform.memory.consolidation.LlmUserProfileConsolidator;
import com.yin.ai.platform.memory.consolidation.DefaultMemoryConsolidationService;
import com.yin.ai.platform.memory.consolidation.EpisodeExtractionConsolidator;
import com.yin.ai.platform.memory.consolidation.FactExtractionConsolidator;
import com.yin.ai.platform.memory.consolidation.MemoryConsolidator;
import com.yin.ai.platform.memory.consolidation.MemoryEpisodePort;
import com.yin.ai.platform.memory.consolidation.RedisSessionConsolidationSchedule;
import com.yin.ai.platform.memory.consolidation.SessionConsolidationSchedule;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.memory.orchestration.DefaultMemoryAccessPolicy;
import com.yin.ai.platform.memory.orchestration.DefaultMemoryContextBuilder;
import com.yin.ai.platform.memory.orchestration.DefaultMemoryOrchestrator;
import com.yin.ai.platform.memory.orchestration.DefaultMemoryRetrievalPolicy;
import com.yin.ai.platform.memory.orchestration.DefaultMemoryWritePolicy;
import com.yin.ai.platform.memory.orchestration.MemoryAccessPolicy;
import com.yin.ai.platform.memory.orchestration.MemoryCandidateClassifier;
import com.yin.ai.platform.memory.orchestration.MemoryConsolidationService;
import com.yin.ai.platform.memory.orchestration.MemoryForgetService;
import com.yin.ai.platform.memory.orchestration.MemoryOrchestrator;
import com.yin.ai.platform.memory.orchestration.MemoryRetrievalPolicy;
import com.yin.ai.platform.memory.orchestration.MemoryWritePolicy;
import com.yin.ai.platform.memory.orchestration.LlmMemoryCandidateClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.memory.search.ElasticsearchMemorySearchService;
import com.yin.ai.platform.core.memory.semantic.EmbeddingService;
import com.yin.ai.platform.adapter.memory.embedding.LangChain4jMemoryEmbeddingService;
import com.yin.ai.platform.memory.semantic.TemporalSemanticMemoryService;
import com.yin.ai.platform.core.memory.procedural.ProceduralMemoryStore;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;
import com.yin.ai.platform.core.memory.store.WorkingMemoryStore;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.adapter.memory.redis.RedisEpisodicMemoryStore;
import com.yin.ai.platform.adapter.memory.redis.RedisWorkingMemoryStore;
import com.yin.ai.platform.memory.working.DefaultWorkingMemoryManager;
import com.yin.ai.platform.memory.working.WorkingMemoryManager;
import org.redisson.api.RedissonClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import io.micrometer.core.instrument.MeterRegistry;
import com.yin.ai.platform.memory.observe.MemoryMetrics;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.yin.ai.platform.memory.config.MemoryProperties;

@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {

    // ==================== Store ====================

    @Bean
    @Primary
    @ConditionalOnMissingBean(EpisodicMemoryStore.class)
    @ConditionalOnProperty(name = "ai.memory.store.default-type", havingValue = "IN_MEMORY",
            matchIfMissing = true)
    public EpisodicMemoryStore episodicMemoryStore(MemoryProperties properties) {
        return new InMemoryMemoryStoreAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(WorkingMemoryStore.class)
    @ConditionalOnProperty(name = "ai.memory.store.default-type", havingValue = "IN_MEMORY",
            matchIfMissing = true)
    public WorkingMemoryStore workingMemoryStore() {
        return new InMemoryMemoryStoreAdapter();
    }

    // ==================== Redis Store（生产环境推荐，ai.memory.store.default-type=REDIS） ====================

    @Bean
    @Primary
    @ConditionalOnMissingBean(EpisodicMemoryStore.class)
    @ConditionalOnProperty(name = "ai.memory.store.default-type", havingValue = "REDIS")
    public EpisodicMemoryStore redisEpisodicMemoryStore(RedissonClient redissonClient,
                                                        MemoryProperties properties) {
        return new RedisEpisodicMemoryStore(redissonClient, null,
                properties.effectiveEpisodicTtl());
    }

    @Bean
    @ConditionalOnMissingBean(WorkingMemoryStore.class)
    @ConditionalOnProperty(name = "ai.memory.store.default-type", havingValue = "REDIS")
    public WorkingMemoryStore redisWorkingMemoryStore(RedissonClient redissonClient,
                                                       MemoryProperties properties) {
        return new RedisWorkingMemoryStore(redissonClient, null,
                properties.effectiveWorkingTtl());
    }

    // ==================== ES 全文检索（可选，ElasticsearchClient 存在时自动启用） ====================

    @Bean
    @ConditionalOnBean(ElasticsearchClient.class)
    @ConditionalOnMissingBean(ElasticsearchMemorySearchService.class)
    public ElasticsearchMemorySearchService elasticsearchMemorySearchService(ElasticsearchClient client) {
        var service = new ElasticsearchMemorySearchService(client);
        service.init();
        return service;
    }

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    @ConditionalOnMissingBean(EmbeddingService.class)
    public EmbeddingService memoryEmbeddingService(EmbeddingModel embeddingModel) {
        return new LangChain4jMemoryEmbeddingService(embeddingModel);
    }

    // ==================== Manager ====================

    @Bean
    @ConditionalOnMissingBean(WorkingMemoryManager.class)
    public WorkingMemoryManager workingMemoryManager(WorkingMemoryStore store) {
        return new DefaultWorkingMemoryManager(store);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryRuntime.class)
    public MemoryRuntime memoryRuntime(WorkingMemoryManager workingMemoryManager,
                                        EpisodicMemoryStore episodicStore,
                                        ObjectProvider<ElasticsearchMemorySearchService> esSearchProvider,
                                        ObjectProvider<EmbeddingService> embeddingServiceProvider,
                                        ObjectProvider<com.yin.ai.platform.memory.audit.MemoryAuditStore> auditStoreProvider,
                                        ObjectProvider<com.yin.ai.platform.agent.trace.StepTraceRecorder> stepTraceProvider,
                                        MemoryProperties properties) {
        DefaultMemoryRuntime runtime = new DefaultMemoryRuntime(workingMemoryManager, episodicStore);
        runtime.setRetrievalProperties(properties.getRetrieval());
        esSearchProvider.ifAvailable(runtime::setEsSearchService);
        embeddingServiceProvider.ifAvailable(runtime::setEmbeddingService);
        auditStoreProvider.ifAvailable(runtime::setAuditStore);
        // [全链路追踪] 记忆环节埋点记录器（service 层 JDBC 实现，未装配 NOOP）
        stepTraceProvider.ifAvailable(runtime::setStepTraceRecorder);
        return runtime;
    }

    // ==================== Policy-governed orchestration ====================

    @Bean
    @ConditionalOnMissingBean(MemoryWritePolicy.class)
    public MemoryWritePolicy memoryWritePolicy(
            ObjectProvider<MemoryCandidateClassifier> classifierProvider) {
        return new DefaultMemoryWritePolicy(classifierProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(MemoryCandidateClassifier.class)
    @ConditionalOnBean(ModelAdapter.class)
    public MemoryCandidateClassifier memoryCandidateClassifier(ModelAdapter modelAdapter,
                                                                ObjectMapper objectMapper) {
        return new LlmMemoryCandidateClassifier(modelAdapter, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryRetrievalPolicy.class)
    public MemoryRetrievalPolicy memoryRetrievalPolicy(MemoryProperties properties) {
        return new DefaultMemoryRetrievalPolicy(
                properties.getOrchestration().getDefaultTokenBudget());
    }

    @Bean
    @ConditionalOnMissingBean(MemoryAccessPolicy.class)
    public MemoryAccessPolicy memoryAccessPolicy() {
        return new DefaultMemoryAccessPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(DefaultMemoryContextBuilder.class)
    public DefaultMemoryContextBuilder memoryContextBuilder() {
        return new DefaultMemoryContextBuilder();
    }

    @Bean
    @ConditionalOnMissingBean(MemoryOrchestrator.class)
    @ConditionalOnProperty(name = "ai.memory.orchestration.enabled", havingValue = "true",
            matchIfMissing = true)
    public MemoryOrchestrator memoryOrchestrator(
            MemoryRuntime runtime,
            MemoryWritePolicy writePolicy,
            MemoryRetrievalPolicy retrievalPolicy,
            MemoryAccessPolicy accessPolicy,
            DefaultMemoryContextBuilder contextBuilder,
            ObjectProvider<TemporalSemanticMemoryService> semanticProvider,
            ObjectProvider<MemoryConsolidationService> consolidationProvider,
            ObjectProvider<MemoryForgetService> forgetProvider,
            ObjectProvider<MemoryMetrics> metricsProvider,
            ObjectProvider<io.opentelemetry.api.OpenTelemetry> openTelemetryProvider,
            MemoryProperties properties) {
        io.opentelemetry.api.OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
        io.opentelemetry.api.trace.Tracer tracer = openTelemetry != null
                ? openTelemetry.getTracer("com.yin.ai.memory") : null;
        DefaultMemoryOrchestrator orchestrator = new DefaultMemoryOrchestrator(runtime, writePolicy, retrievalPolicy,
                accessPolicy, contextBuilder, semanticProvider.getIfAvailable(),
                consolidationProvider.getIfAvailable(), forgetProvider.getIfAvailable(),
                properties.getOrchestration().isShadowMode(), tracer);
        MemoryConsolidationService consolidationService = consolidationProvider.getIfAvailable();
        if (consolidationService instanceof DefaultMemoryConsolidationService defaultService) {
            defaultService.setOrchestrator(orchestrator);
        }
        metricsProvider.ifAvailable(orchestrator::setMetrics);
        return orchestrator;
    }

    @Bean
    @ConditionalOnMissingBean(MemoryContextProvider.class)
    @ConditionalOnBean(MemoryOrchestrator.class)
    @ConditionalOnProperty(name = "ai.memory.orchestration.retrieval-enabled", havingValue = "true")
    public MemoryContextProvider memoryContextProvider(MemoryOrchestrator orchestrator) {
        return new DefaultMemoryContextProvider(orchestrator);
    }

    // ==================== Compression ====================

    @Bean
    @ConditionalOnMissingBean(MemoryCompressor.class)
    @ConditionalOnBean(ModelAdapter.class)
    public MemoryCompressor memoryCompressor(ModelAdapter modelAdapter) {
        Function<List<MemoryCell>, String> llmSummarizeFn = cells -> {
            if (cells == null || cells.isEmpty()) {
                return "";
            }
            String conversation = cells.stream()
                    .map(c -> {
                        String role = (String) c.attributes().getOrDefault("role", "user");
                        return "[" + role + "]: " + c.content();
                    })
                    .collect(Collectors.joining("\n"));

            GatewayRequest request = new GatewayRequest();
            request.setMessages(List.of(
                    GatewayMessage.system("你是一个对话摘要器。将以下对话压缩为一段简洁的摘要（不超过300字），保留关键信息和决策。"),
                    GatewayMessage.user(conversation)));
            GatewayOptions options = new GatewayOptions();
            options.setModel("deepseek-chat");
            options.setTemperature(0.1);
            options.setMaxTokens(512);
            options.setTimeout(Duration.ofSeconds(15));
            request.setOptions(options);
            request.setTraceId("mem-compress-" + UUID.randomUUID().toString().substring(0, 8));
            request.setCaller("SummarizationCompressor");

            try {
                return modelAdapter.call(request).getContent();
            } catch (Exception e) {
                return cells.stream()
                        .map(MemoryCell::content)
                        .collect(Collectors.joining(" | "));
            }
        };
        return new SummarizationCompressor(llmSummarizeFn);
    }

    // ==================== LLM Consolidation ====================

    @Bean
    @ConditionalOnMissingBean(LlmFactExtractionConsolidator.class)
    @ConditionalOnBean(ModelAdapter.class)
    public LlmFactExtractionConsolidator llmFactExtractionConsolidator(ModelAdapter modelAdapter) {
        return new LlmFactExtractionConsolidator(modelAdapter);
    }

    @Bean
    @ConditionalOnMissingBean(LlmUserProfileConsolidator.class)
    @ConditionalOnBean(ModelAdapter.class)
    public LlmUserProfileConsolidator llmUserProfileConsolidator(ModelAdapter modelAdapter) {
        return new LlmUserProfileConsolidator(modelAdapter);
    }

    @Bean
    @ConditionalOnMissingBean(EpisodeExtractionConsolidator.class)
    public EpisodeExtractionConsolidator episodeExtractionConsolidator() {
        return new EpisodeExtractionConsolidator();
    }

    @Bean
    @ConditionalOnMissingBean(FactExtractionConsolidator.class)
    public FactExtractionConsolidator factExtractionConsolidator() {
        return new FactExtractionConsolidator();
    }

    @Bean
    @ConditionalOnMissingBean(MemoryConsolidationService.class)
    @ConditionalOnProperty(name = "ai.memory.orchestration.consolidation-enabled", havingValue = "true")
    public MemoryConsolidationService memoryConsolidationService(
            EpisodicMemoryStore episodicStore,
            List<MemoryConsolidator> consolidators,
            ObjectProvider<ProceduralMemoryStore> procedureStoreProvider,
            ObjectProvider<MemoryEpisodePort> episodePortProvider) {
        boolean hasLlmFacts = consolidators.stream()
                .anyMatch(LlmFactExtractionConsolidator.class::isInstance);
        List<MemoryConsolidator> effective = consolidators.stream()
                .filter(value -> !hasLlmFacts || !(value instanceof FactExtractionConsolidator))
                .toList();
        return new DefaultMemoryConsolidationService(episodicStore, effective,
                procedureStoreProvider.getIfAvailable(), episodePortProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(SessionConsolidationSchedule.class)
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnProperty(name = "ai.memory.orchestration.consolidation-enabled", havingValue = "true")
    public SessionConsolidationSchedule sessionConsolidationSchedule(
            RedissonClient redissonClient, MemoryConsolidationService service) {
        return new RedisSessionConsolidationSchedule(redissonClient, service);
    }

    // ==================== Cache ====================

    @Bean
    @ConditionalOnMissingBean(TieredMemoryCacheManager.class)
    public TieredMemoryCacheManager tieredMemoryCacheManager(MemoryProperties properties) {
        return new TieredMemoryCacheManager(
                properties.getL1().getMaxSize(),
                properties.getL1().getTtl(),
                properties.getL1().isRecordStats());
    }

    @Bean
    @ConditionalOnMissingBean(MemoryMetrics.class)
    @ConditionalOnBean(MeterRegistry.class)
    public MemoryMetrics memoryMetrics(MeterRegistry meterRegistry) {
        return new MemoryMetrics(meterRegistry);
    }

    // ==================== Maintenance（P1-1 修复：注册调度器及依赖，使定时整合真正生效） ====================

    @Bean
    @ConditionalOnMissingBean
    public com.yin.ai.platform.memory.maintenance.MemoryMaintenanceConfig memoryMaintenanceConfig() {
        return com.yin.ai.platform.memory.maintenance.MemoryMaintenanceConfig.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.yin.ai.platform.memory.policy.MemoryPolicyEngine memoryPolicyEngine() {
        return new com.yin.ai.platform.memory.policy.MemoryPolicyEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.yin.ai.platform.memory.maintenance.MemoryMaintenanceScheduler memoryMaintenanceScheduler(
            MemoryRuntime memoryRuntime,
            com.yin.ai.platform.memory.policy.MemoryPolicyEngine policyEngine,
            java.util.List<com.yin.ai.platform.memory.consolidation.MemoryConsolidator> consolidators,
            com.yin.ai.platform.memory.maintenance.MemoryMaintenanceConfig config) {
        return new com.yin.ai.platform.memory.maintenance.MemoryMaintenanceScheduler(
                memoryRuntime, policyEngine, consolidators, config);
    }
}
