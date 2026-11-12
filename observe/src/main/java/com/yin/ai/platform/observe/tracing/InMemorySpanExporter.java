package com.yin.ai.platform.observe.tracing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

/**
 * 内存 Span 导出器 — 将 OpenTelemetry Span 写入 {@link TraceStore}，
 * 供管理端全链路追踪 API 查询。
 * <p>
 * 与 {@link Slf4jSpanExporter} 共存，不冲突。
 */
@Slf4j
public class InMemorySpanExporter implements SpanExporter {

    private final TraceStore storage;

    public InMemorySpanExporter(TraceStore storage) {
        this.storage = storage;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData data : spans) {
            if (data.getSpanContext().isSampled()) {
                storage.store(TraceSpan.fromSpanData(data));
            }
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
