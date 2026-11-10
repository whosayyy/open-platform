package com.yin.ai.platform.knowledge;

import com.yin.ai.platform.engine.metrics.AiMetrics;
import com.yin.ai.platform.adapter.rag.config.RAGProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库模块 Spring 配置 — 仅在 {@code ai.knowledge.enabled=true} 时激活。
 * <p>
 * 遍历 {@link KnowledgeProperties#getBases()} 中配置的每个知识库，
 * 按 type 创建对应的 {@link KnowledgeBase} 实现并注册到 {@link KnowledgeRegistry}。
 */
@Configuration
@ConditionalOnProperty(name = "ai.knowledge.enabled", havingValue = "true", matchIfMissing = false)
public class KnowledgeConfiguration {

    @Bean
    @ConfigurationProperties("ai.knowledge")
    KnowledgeProperties knowledgeProperties() {
        return new KnowledgeProperties();
    }

    @Bean
    KnowledgeRegistry knowledgeRegistry(
            RestClient restClient,
            EmbeddingModel embeddingModel,
            KnowledgeProperties knowledgeProps,
            RAGProperties ragProps,
            AiMetrics aiMetrics) {

        List<KnowledgeBase> bases = new ArrayList<>();
        for (var entry : knowledgeProps.getBases().entrySet()) {
            String name = entry.getKey();
            KnowledgeProperties.KnowledgeBaseConfig config = entry.getValue();

            if ("vector".equals(config.getType())) {
                String indexName = config.getIndexName();
                if (indexName == null || indexName.isBlank()) {
                    indexName = ragProps.getEsIndex();
                }

                EmbeddingStore<TextSegment> store = ElasticsearchEmbeddingStore.builder()
                        .restClient(restClient)
                        .indexName(indexName)
                        .build();

                VectorKnowledgeBase kb = new VectorKnowledgeBase(
                        name, store, embeddingModel, config,
                        knowledgeProps.getMaxContextTokens(), aiMetrics);
                bases.add(kb);
            }
            // 其他 type（keyword / graph / hybrid）待扩展
        }

        return new KnowledgeRegistry(bases);
    }
}
