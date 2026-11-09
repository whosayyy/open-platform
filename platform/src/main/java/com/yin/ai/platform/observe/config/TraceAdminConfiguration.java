package com.yin.ai.platform.observe.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.yin.ai.platform.observe.tracing.ElasticsearchTraceStore;
import com.yin.ai.platform.observe.tracing.InMemorySpanExporter;
import com.yin.ai.platform.observe.tracing.TraceContextHttpInterceptor;
import com.yin.ai.platform.observe.tracing.TraceStore;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

/**
 * 全链路追踪管理配置。
 * <p>
 * <b>管理端 API + 存储：</b>始终可用（无 condition），允许管理端查询 Trace 数据。<br>
 * <b>数据采集：</b>仅在 {@code ai.observability.tracing.enabled=true} 时激活，
 * 将 OpenTelemetry Span 写入 ES。关闭时 API 返回空数据但不 404。
 */
@Configuration
public class TraceAdminConfiguration {

    /** 复用 RAG 的 ES 连接配置 */
    @Value("${ai.rag.es-hosts:localhost:9200}")
    private String esHosts;

    @Value("${ai.rag.es-username:elastic}")
    private String esUsername;

    @Value("${ai.rag.es-password:}")
    private String esPassword;

    @Value("${ai.rag.es-insecure:false}")
    private boolean esInsecure;

    // ==================== Elasticsearch 客户端（始终创建） ====================

    @Bean
    @ConditionalOnMissingBean
    RestClient traceRestClient() {
        try {
            RestClientBuilder builder = RestClient.builder(HttpHost.create(esHosts));

            if (esHosts.startsWith("https") && esInsecure) {
                SSLContext sslContext = SSLContexts.custom()
                        .loadTrustMaterial(null, (chain, authType) -> true)
                        .build();
                configureHttpClient(builder, sslContext, esUsername, esPassword);
            } else if (!esUsername.isEmpty()) {
                configureHttpClientAuth(builder, esUsername, esPassword);
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ES RestClient for tracing", e);
        }
    }

    private void configureHttpClient(RestClientBuilder builder, SSLContext sslContext,
                                      String username, String password) {
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            httpClientBuilder
                    .setSSLContext(sslContext)
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            if (!username.isEmpty()) {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
            return httpClientBuilder;
        });
    }

    private void configureHttpClientAuth(RestClientBuilder builder,
                                          String username, String password) {
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(username, password));
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            return httpClientBuilder;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    ElasticsearchClient traceElasticsearchClient(RestClient restClient) {
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }

    /** ES 持久化 Trace 存储，管理端 API 通过它查询。始终可用。 */
    @Bean
    @ConditionalOnMissingBean
    TraceStore traceStore(ElasticsearchClient client) {
        var store = new ElasticsearchTraceStore(client);
        store.init();
        return store;
    }

    // ==================== 数据采集（仅 tracing.enabled=true 时激活） ====================

    /** 将 OpenTelemetry Span 写入 TraceStore（与 Slf4jSpanExporter 共存）。 */
    @Bean
    @ConditionalOnMissingBean(name = "inMemorySpanExporter")
    @ConditionalOnProperty(name = "ai.observability.tracing.enabled", havingValue = "true", matchIfMissing = false)
    SpanExporter inMemorySpanExporter(TraceStore store) {
        return new InMemorySpanExporter(store);
    }

    /** 往 LLM 出站 RestClient 注入 W3C Trace Context。 */
    @Bean
    @ConditionalOnClass(OpenAiApi.class)
    @ConditionalOnProperty(name = "ai.observability.tracing.enabled", havingValue = "true", matchIfMissing = false)
    RestClientCustomizer traceContextRestClientCustomizer() {
        return builder -> builder.requestInterceptor(new TraceContextHttpInterceptor());
    }
}
