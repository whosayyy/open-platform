package com.yin.ai.platform.observe.tracing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

/**
 * SLF4J Span 导出器 — 将 Span 数据输出到日志。
 * <p>
 * 生产环境建议替换为 {@code OtlpHttpSpanExporter}（Jaeger / Grafana Tempo），
 * 只需在 classpath 添加 {@code opentelemetry-exporter-otlp} 并注册对应 Bean 即可。
 */
@Slf4j
public class Slf4jSpanExporter implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData span : spans) {
            log.info("[OTEL] traceId={} spanId={} parentSpanId={} name={} kind={} status={} durationMs={} attrs={}",
                    span.getTraceId(),
                    span.getSpanId(),
                    span.getParentSpanId(),
                    span.getName(),
                    span.getKind(),
                    span.getStatus().getStatusCode(),
                    (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000,
                    span.getAttributes().asMap());
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
