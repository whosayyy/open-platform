package com.yin.ai.platform.rag.config;

import com.yin.ai.platform.adapter.rag.embedding.DocumentIngestor;
import com.yin.ai.platform.adapter.rag.config.RAGProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

/**
 * LangChain4j RAG 条件配置 — 仅在 {@code ai.rag.enabled=true} 时激活。
 * <p>
 * 索引创建（含 IK 中文分词）由 {@link com.yin.ai.platform.rag.ElasticsearchDocService} 负责；
 * 本配置专注于 langchain4j 组件装配。
 */
@Configuration
@ConditionalOnProperty(name = "ai.rag.enabled", havingValue = "true", matchIfMissing = false)
public class LangChain4jRAGConfig {

    @Bean
    @ConfigurationProperties("ai.rag")
    RAGProperties ragProperties() {
        return new RAGProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    RestClient restClient(RAGProperties props) throws Exception {
        var builder = RestClient.builder(HttpHost.create(props.getEsHosts()));

        if (props.getEsHosts().startsWith("https") && props.isEsInsecure()) {
            SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                httpClientBuilder
                        .setSSLContext(sslContext)
                        .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                if (!props.getEsPassword().isEmpty()) {
                    BasicCredentialsProvider cp = new BasicCredentialsProvider();
                    cp.setCredentials(AuthScope.ANY,
                            new UsernamePasswordCredentials(props.getEsUsername(), props.getEsPassword()));
                    httpClientBuilder.setDefaultCredentialsProvider(cp);
                }
                return httpClientBuilder;
            });
        } else if (!props.getEsPassword().isEmpty()) {
            BasicCredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(props.getEsUsername(), props.getEsPassword()));
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                httpClientBuilder.setDefaultCredentialsProvider(cp);
                return httpClientBuilder;
            });
        }

        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    EmbeddingModel embeddingModel() {
        return new BgeSmallZhEmbeddingModel();
    }

    @Bean
    @ConditionalOnMissingBean
    EmbeddingStore<TextSegment> embeddingStore(RestClient restClient, RAGProperties props) {
        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(props.getEsIndex())
                .build();
    }

    @Bean
    DocumentIngestor documentIngestor(EmbeddingModel embeddingModel,
                                      EmbeddingStore<TextSegment> embeddingStore,
                                      RAGProperties ragProperties,
                                      java.util.List<com.yin.ai.platform.document.splitter.ChunkSplitter> splitters) {
        return new DocumentIngestor(embeddingModel, embeddingStore, ragProperties, splitters);
    }
}
