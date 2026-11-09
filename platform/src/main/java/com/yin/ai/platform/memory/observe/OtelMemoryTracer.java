package com.yin.ai.platform.memory.observe;

import com.yin.ai.platform.memory.event.MemoryEvent;
import com.yin.ai.platform.memory.event.MemoryEventBus;
import com.yin.ai.platform.memory.event.MemoryEventListener;
import com.yin.ai.platform.memory.event.MemoryEventType;
import com.yin.ai.platform.observe.tracing.AiTracingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * OpenTelemetry 记忆链路追踪器 — 将 MemoryEvent 转为 OTel Span。
 *
 * <p>【改动原因】将 MemoryTracer（InMemory，注释标注"生产环境应接入 OTel"）升级为
 * 同时输出 OTel Span，对齐 09-Observability.md §7 Tracing 体系。
 * 仅在 AiTracingService 可用时激活（ai.observability.tracing.enabled=true）。
 *
 * <p>与 {@link MemoryTracer} 互补：
 * <ul>
 *   <li>MemoryTracer：InMemory 存储，供调试端点查询（/api/agent/admin/memory/traces）</li>
 *   <li>OtelMemoryTracer：OTel Span 输出，供 Jaeger/Grafana Tempo 可视化（生产环境）</li>
 * </ul>
 *
 * <p>Span 命名：ai.memory.{eventType}（如 ai.memory.write / ai.memory.read / ai.memory.consolidate）。
 * 对齐 09-Observability.md §13 日志规范 [MEMORY] 前缀。
 */
@Slf4j
@Component
@ConditionalOnBean(AiTracingService.class)
public class OtelMemoryTracer implements MemoryEventListener {

    private final ObjectProvider<AiTracingService> tracingProvider;

    public OtelMemoryTracer(ObjectProvider<AiTracingService> tracingProvider,
                             MemoryEventBus eventBus) {
        this.tracingProvider = tracingProvider;
        // 注册到事件总线（与 MemoryTracer 并行监听）
        eventBus.register(this);
        log.info("[OTEL-MEMORY] OtelMemoryTracer 已注册到 MemoryEventBus");
    }

    @Override
    public void onEvent(MemoryEvent event) {
        AiTracingService tracing = tracingProvider.getIfAvailable();
        if (tracing == null) return;

        String spanName = "ai.memory." + event.type().name().toLowerCase();
        try (AiTracingService.SpanScope span = tracing.startSpan(spanName)) {
            // 核心属性
            if (event.traceId() != null) {
                span.setAttribute("memory.traceId", event.traceId());
            }
            span.setAttribute("memory.type", event.type().name());
            if (event.memoryId() != null) {
                span.setAttribute("memory.id", event.memoryId().id());
            }

            // 附加数据属性（低基数，只取 key 数量和关键值）
            if (event.data() != null && !event.data().isEmpty()) {
                span.setAttribute("memory.dataKeys", event.data().keySet().toString());
            }

            span.setOk();
        } catch (Exception e) {
            log.debug("[OTEL-MEMORY] span 创建失败（不阻塞）: {}", e.getMessage());
        }
    }

    @Override
    public boolean supports(MemoryEventType type) {
        // 关心所有 Memory 事件类型
        return true;
    }
}
