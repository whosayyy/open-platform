package com.yin.ai.platform.observe.config;

import com.yin.ai.platform.observe.tracing.AiTracingService;
import com.yin.ai.platform.observe.tracing.Slf4jSpanExporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenTelemetry Tracing 配置 — 条件激活于 {@code ai.observability.tracing.enabled=true}。
 * <p>
 * 生产环境可通过注册自定义 {@link SpanExporter} Bean（如 OtlpHttpSpanExporter）来
 * 覆盖默认的 Slf4jSpanExporter，将 Trace 数据发送到 Jaeger / Grafana Tempo 等后端。
 */
@Configuration
@ConditionalOnProperty(name = "ai.observability.tracing.enabled", havingValue = "true", matchIfMissing = false)
public class OpenTelemetryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SpanExporter spanExporter() {
        return new Slf4jSpanExporter();
    }

    @Bean
    SdkTracerProvider sdkTracerProvider(List<SpanExporter> exporters) {
        var builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn());
        for (SpanExporter exporter : exporters) {
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }
        return builder.build();
    }

    @Bean
    OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Bean
    AiTracingService aiTracingService(OpenTelemetry openTelemetry) {
        return new AiTracingService(openTelemetry);
    }
}
