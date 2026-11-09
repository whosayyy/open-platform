package com.yin.ai.platform.function.config;

import com.yin.ai.platform.engine.TaskManager;
import com.yin.ai.platform.filter.YamlSensitiveWordProvider;
import com.yin.ai.platform.function.FunctionFallbackProvider;
import com.yin.ai.platform.function.FunctionOrchestrator;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.function.EmbeddingFunction;
import com.yin.ai.platform.function.ReportPlanFunction;
import com.yin.ai.platform.function.RecallMemoryFunction;
import com.yin.ai.platform.function.WebPageFunction;
import com.yin.ai.platform.function.TranslateFunction;
import com.yin.ai.platform.function.SentimentFunction;
import com.yin.ai.platform.function.FunctionSchemaWarmup;
import com.yin.ai.platform.gateway.GatewayStructuredClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.stream.SinkStreamProducer;
import com.yin.ai.platform.gateway.stream.StreamMetrics;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Function Framework Spring 配置。
 */
@Configuration
@EnableConfigurationProperties(YamlSensitiveWordProvider.class)
public class FunctionConfiguration {

    @Bean
    public FunctionRegistry functionRegistry() {
        return new FunctionRegistry();
    }

    @Bean
    public TranslateFunction translateFunction(GatewayStructuredClient client, ObjectMapper objectMapper) {
        return new TranslateFunction(client, objectMapper);
    }

    @Bean
    public SentimentFunction sentimentFunction(GatewayStructuredClient client, ObjectMapper objectMapper) {
        return new SentimentFunction(client, objectMapper);
    }

    @Bean
    public FunctionSchemaWarmup functionSchemaWarmup(FunctionRegistry registry) {
        return new FunctionSchemaWarmup(registry);
    }

    @Bean
    public ReportPlanFunction reportPlanFunction() {
        return new ReportPlanFunction();
    }

    /**
     * [B-agentic] recallMemory 工具：LLM 按需召回历史记忆。
     * MemoryRuntime 可能未装配（ai.memory 未启用）——用 ObjectProvider 降级，
     * 未装配时返回 null（FunctionRegistry 扫描不到该 Bean，不注册）。
     */
    @Bean
    public RecallMemoryFunction recallMemoryFunction(
            ObjectProvider<MemoryRuntime> memoryRuntimeProvider) {
        MemoryRuntime runtime = memoryRuntimeProvider.getIfAvailable();
        if (runtime == null) {
            return null;
        }
        return new RecallMemoryFunction(runtime);
    }

    @Bean
    public WebPageFunction webPageFunction() {
        return new WebPageFunction();
    }

    @Bean
    public EmbeddingFunction embeddingFunction(
            @Value("${ai.embedding.api-key:}") String apiKey,
            @Value("${ai.embedding.api-url:http://localhost:8000/v1/embeddings}") String apiUrl,
            @Value("${ai.embedding.model:text-embedding-v3}") String model) {
        return new EmbeddingFunction(apiKey, apiUrl, model);
    }

    @Bean
    public FunctionOrchestrator functionOrchestrator(FunctionRegistry functionRegistry,
                                                      List<FunctionFallbackProvider> fallbackProviders,
                                                      SinkStreamProducer streamProducer,
                                                      TaskManager taskManager,
                                                      StreamMetrics metrics) {
        return new FunctionOrchestrator(functionRegistry, fallbackProviders, streamProducer, taskManager, metrics);
    }
}
