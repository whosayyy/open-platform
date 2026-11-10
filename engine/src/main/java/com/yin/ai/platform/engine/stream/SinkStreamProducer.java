package com.yin.ai.platform.engine.stream;

import com.yin.ai.platform.config.PipelineConfig;
import com.yin.ai.platform.engine.EngineProperties;
import com.yin.ai.platform.engine.TaskCanceller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式生产者：管理每个任务的 Sink 管道与序号计数器。
 * 有界缓冲 + 溢出丢弃策略，保护 JVM 内存。
 */
@Slf4j
@Component
public class SinkStreamProducer {

    private final ConcurrentHashMap<String, Sinks.Many<StreamPacket>> sinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuffer> textAccumulators = new ConcurrentHashMap<>();
    /** 延迟取消任务追踪（onClientDisconnect=true 时，给前端重连留宽限期） */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingCancels = new ConcurrentHashMap<>();
    private final int bufferSize;
    private final TaskCanceller taskCanceller;
    private final StreamMetrics metrics;
    private final EngineProperties engineProperties;
    private final ScheduledExecutorService cancelScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sink-cancel-scheduler");
        t.setDaemon(true);
        return t;
    });

    public SinkStreamProducer(PipelineConfig config, TaskCanceller taskCanceller,
                              StreamMetrics metrics, EngineProperties engineProperties) {
        this.bufferSize = config.getSinkBufferSize();
        this.taskCanceller = taskCanceller;
        this.metrics = metrics;
        this.engineProperties = engineProperties;
    }

    public void initialize(String taskId) {
        initialize(taskId, 0);
    }

    /**
     * 初始化指定起始序号的 sink。任务恢复时需要从历史 SSE 序号之后继续发送，
     * 否则同一 taskId 的 Last-Event-ID 会出现重复或倒退。
     */
    public void initialize(String taskId, long initialSeq) {
        cancelPendingCancellation(taskId);
        // 任务 SSE 允许客户端断线后重新订阅。unicast sink 在首个订阅者取消后
        // 不允许第二个订阅者接入，会把后端仍在执行的任务误判为 SSE 错误。
        // multicast + 有界回放既支持重连，也避免无界缓存；Redis history 负责跨实例补偿。
        Sinks.Many<StreamPacket> sink = Sinks.many()
                .replay()
                .limit(bufferSize);
        sinks.put(taskId, sink);
        seqCounters.put(taskId, new AtomicLong(Math.max(0, initialSeq)));
        textAccumulators.put(taskId, new StringBuffer());
        metrics.incrementTotalTasks();
    }

    public Flux<StreamPacket> asFlux(String taskId) {
        Sinks.Many<StreamPacket> sink = sinks.get(taskId);
        if (sink == null) return Flux.empty();
        return sink.asFlux()
                .doOnSubscribe(subscription -> cancelPendingCancellation(taskId))
                .doOnCancel(() -> {
                    log.info("客户端断连, taskId={}", taskId);
                    metrics.incrementClientDisconnects();
                    EngineProperties.Cancellation cancelConfig = engineProperties.getCancellation();
                    if (!cancelConfig.isOnClientDisconnect()) {
                        // 默认：不取消任务，让 Agent 继续运行（结果持久化到 Redis，前端可恢复）
                        log.info("onClientDisconnect=false，任务继续运行, taskId={}", taskId);
                        return;
                    }
                    // onClientDisconnect=true：延迟取消，给前端切页面/重连留出宽限期
                    int graceSeconds = cancelConfig.getDisconnectGraceSeconds();
                    log.info("onClientDisconnect=true，{}s 后取消任务, taskId={}", graceSeconds, taskId);
                    ScheduledFuture<?> future = cancelScheduler.schedule(() -> {
                        log.info("宽限期到期，执行取消, taskId={}", taskId);
                        taskCanceller.cancel(taskId);
                    }, graceSeconds, TimeUnit.SECONDS);
                    ScheduledFuture<?> existing = pendingCancels.put(taskId, future);
                    if (existing != null) existing.cancel(false);
                });
    }

    /**
     * 订阅指定序号之后的实时事件。回放 sink 会先重放内存缓冲，
     * 这里过滤掉 Last-Event-ID 之前的事件，避免 Redis history 与 sink 回放重复。
     */
    public Flux<StreamPacket> asFlux(String taskId, long afterSeq) {
        return asFlux(taskId).filter(packet -> packet.getSeq() > afterSeq);
    }

    private Sinks.EmitResult emit(String taskId, StreamPacket packet) {
        Sinks.Many<StreamPacket> sink = sinks.get(taskId);
        if (sink == null) return Sinks.EmitResult.FAIL_CANCELLED;
        packet.setTaskId(taskId);
        AtomicLong counter = seqCounters.get(taskId);
        if (packet.getTs() > 0) {
            // A durable history writer may already have assigned the authoritative
            // sequence. Preserve it so replay and live delivery use one cursor.
            counter.accumulateAndGet(packet.getSeq() + 1, Math::max);
        } else {
            packet.setSeq(counter.getAndIncrement());
            packet.setTs(System.currentTimeMillis());
        }
        packet.setSequence(packet.getSeq());
        packet.setTimestamp(java.time.Instant.ofEpochMilli(packet.getTs()).toString());
        if (packet.getEventId() == null || packet.getEventId().isBlank()) {
            packet.setEventId(String.valueOf(packet.getSeq()));
        }

        Sinks.EmitResult result = sink.tryEmitNext(packet);
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            log.warn("Sink 溢出, taskId={}, seq={}, type={}", taskId, packet.getSeq(), packet.getType());
            metrics.incrementOverflowDrops();
        } else if (result == Sinks.EmitResult.FAIL_CANCELLED) {
            log.debug("Sink 已取消（客户端断连）, taskId={}", taskId);
        }
        return result;
    }

    /** Emit an already structured packet while retaining the common sequence/timestamp rules. */
    public void emitPacket(String taskId, StreamPacket packet) {
        if (packet != null) {
            if ("text".equals(packet.getType()) && packet.getContent() != null) {
                StringBuffer acc = textAccumulators.get(taskId);
                if (acc != null) acc.append(packet.getContent());
            }
            emit(taskId, packet);
        }
    }

    public void emitThinking(String taskId, String content) {
        emit(taskId, StreamPacket.builder().type("thinking").content(content).build());
    }

    /**
     * 发射 LLM 思考模式 token（DeepSeek reasoning_content）。
     * 与 emitThinking（Agent 生命周期进度）区分：type="reasoning" 是模型推理过程。
     */
    public void emitReasoning(String taskId, String content) {
        emit(taskId, StreamPacket.builder()
                .type("thinking").eventType("reasoning.progress")
                .content("").payload(java.util.Map.of("status", "active")).build());
    }

    public void emitText(String taskId, String content) {
        StringBuffer acc = textAccumulators.get(taskId);
        if (acc != null) acc.append(content);
        emit(taskId, StreamPacket.builder().type("text").content(content).build());
    }

    /**
     * 产物增量：仅推送到 sink（SSE/WS）与 history，不累积到 textAccumulator。
     * 产物全文走右侧 Artifact 面板，不应进入 complete 事件内容（避免对话流输出全文）。
     */
    public void emitArtifactDelta(String taskId, String content) {
        emit(taskId, StreamPacket.builder().type("text").content(content).build());
    }

    public void emitToolCall(String taskId, String stage, String toolName, Object argsOrResult) {
        StreamPacket.ToolCallPayload payload;
        if ("invoking".equals(stage)) {
            payload = StreamPacket.ToolCallPayload.builder()
                    .stage("invoking").toolName(toolName).arguments(argsOrResult).build();
        } else {
            payload = StreamPacket.ToolCallPayload.builder()
                    .stage("result").toolName(toolName).result(argsOrResult).build();
        }
        emit(taskId, StreamPacket.builder().type("tool_call").content(payload).build());
    }

    public void emitToolCallError(String taskId, String toolName, String errorMessage) {
        StreamPacket.ToolCallPayload payload = StreamPacket.ToolCallPayload.builder()
                .stage("result").toolName(toolName).error(errorMessage).build();
        emit(taskId, StreamPacket.builder().type("tool_call").content(payload).build());
    }

    public void emitComplete(String taskId, String fullContent) {
        emit(taskId, StreamPacket.builder().type("complete").content(fullContent).build());
    }

    public void emitError(String taskId, String errorMessage) {
        emit(taskId, StreamPacket.builder().type("error").content(errorMessage).build());
    }

    public void emitReference(String taskId, String referencesJson) {
        emit(taskId, StreamPacket.builder().type("reference").content(referencesJson).build());
    }

    public void complete(String taskId) {
        // 取消可能存在的延迟取消任务（任务已正常完成，无需再 cancel）
        ScheduledFuture<?> pending = pendingCancels.remove(taskId);
        if (pending != null) pending.cancel(false);
        Sinks.Many<StreamPacket> sink = sinks.remove(taskId);
        if (sink != null) sink.tryEmitComplete();
        seqCounters.remove(taskId);
        textAccumulators.remove(taskId);
    }

    /** 检查指定任务的 sink 是否仍然活跃（未 complete/remove） */
    public boolean hasActiveSink(String taskId) {
        return sinks.containsKey(taskId);
    }

    private void cancelPendingCancellation(String taskId) {
        ScheduledFuture<?> pending = pendingCancels.remove(taskId);
        if (pending != null) {
            pending.cancel(false);
            log.debug("客户端已重连，取消延迟任务, taskId={}", taskId);
        }
    }

    public String getAccumulatedText(String taskId) {
        StringBuffer sb = textAccumulators.get(taskId);
        return sb != null ? sb.toString() : "";
    }
}
