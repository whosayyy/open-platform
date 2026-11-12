package com.yin.ai.platform.adapter.runtime.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.event.TaskEventType;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Stream event journal with a live Reactor sink for transport adapters.
 *
 * <p>The runtime publishes transport-neutral {@link AgentEvent} values through
 * {@link EventPublisher}. Redis provides replay after reconnect, while the
 * in-process sink provides low-latency delivery to the Spring SSE adapter.</p>
 */
public class RedisAgentEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisAgentEventPublisher.class);
    private static final String STREAM_PREFIX = "ai:task:";
    private static final String STREAM_SUFFIX = ":events";
    private static final String SEQ_SUFFIX = ":seq";
    private static final Duration STREAM_TTL = Duration.ofHours(24);

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Sinks.Many<AgentEvent>> eventSinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> localSeqFallback = new ConcurrentHashMap<>();

    public RedisAgentEventPublisher(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void publish(AgentEvent event) {
        String taskId = event.taskId();
        long sequence = nextSequence(taskId);

        try {
            writeToStream(taskId, event, sequence);
        } catch (Exception e) {
            log.warn("[EVENT] Redis Stream write failed taskId={}: {}", taskId, e.getMessage());
        }

        AgentEvent delivery = new AgentEvent(String.valueOf(sequence), taskId, event.type(), event.stepId(),
                event.payload(), event.timestamp());
        Sinks.Many<AgentEvent> sink = eventSinks.get(taskId);
        if (sink != null) {
            Sinks.EmitResult result = sink.tryEmitNext(delivery);
            if (result.isFailure()) {
                log.debug("[EVENT] live emit failed taskId={}: {}", taskId, result);
            }
        }
        if (isTerminal(event.type())) {
            cleanupSink(taskId);
        }
    }

    @Override
    public Flux<AgentEvent> replay(String taskId, String lastEventId) {
        long cursor = parseSequenceCursor(lastEventId);
        return Flux.create(sink -> {
            try {
                RStream<String, String> stream = redisson.getStream(streamKey(taskId));
                var messages = stream.range(StreamMessageId.MIN, StreamMessageId.MAX);
                if (messages != null) {
                    for (var entry : messages.entrySet()) {
                        String sequenceField = entry.getValue().get("seq");
                        long sequence = sequenceField == null ? -1 : parseSequence(sequenceField);
                        if (sequence <= cursor) continue;
                        AgentEvent event = deserializeEvent(taskId, entry.getKey(), entry.getValue());
                        if (event != null) sink.next(event);
                    }
                }
                sink.complete();
            } catch (Exception e) {
                log.error("[EVENT] replay failed taskId={}: {}", taskId, e.getMessage());
                sink.error(e);
            }
        });
    }

    /** Subscribe to live events for a task. */
    public Flux<AgentEvent> subscribe(String taskId) {
        Sinks.Many<AgentEvent> sink = eventSinks.computeIfAbsent(taskId, ignored ->
                Sinks.many().replay().limit(128));
        return sink.asFlux().doFinally(signal ->
                log.debug("[EVENT] live subscription ended taskId={} signal={}", taskId, signal));
    }

    /** Replay persisted history first, then continue with live events. */
    public Flux<AgentEvent> subscribeWithReplay(String taskId, String lastEventId) {
        Flux<AgentEvent> history = replay(taskId, lastEventId);
        Flux<AgentEvent> realtime = subscribe(taskId);
        return history.concatWith(realtime).distinct(AgentEvent::id);
    }

    /** Complete and remove the live sink after a task reaches a terminal state. */
    public void cleanupSink(String taskId) {
        Sinks.Many<AgentEvent> sink = eventSinks.remove(taskId);
        if (sink != null) sink.tryEmitComplete();
    }

    private void writeToStream(String taskId, AgentEvent event, long sequence) {
        RStream<String, String> stream = redisson.getStream(streamKey(taskId));
        stream.add(org.redisson.api.stream.StreamAddArgs.entries(Map.of(
                "type", event.type().name(),
                "stepId", event.stepId() != null ? event.stepId() : "",
                "payload", serializePayload(event),
                "timestamp", event.timestamp().toString(),
                "seq", String.valueOf(sequence)
        )));
        if (isTerminal(event.type())) stream.expire(STREAM_TTL);
    }

    private AgentEvent deserializeEvent(String taskId, StreamMessageId id, Map<String, String> fields) {
        try {
            TaskEventType type = TaskEventType.valueOf(fields.get("type"));
            String stepId = fields.get("stepId");
            String eventId = fields.getOrDefault("seq", id.toString());
            return new AgentEvent(
                    eventId,
                    taskId,
                    type,
                    stepId == null || stepId.isEmpty() ? null : stepId,
                    deserializePayload(fields.get("payload")),
                    java.time.Instant.parse(fields.get("timestamp"))
            );
        } catch (Exception e) {
            return null;
        }
    }

    private long nextSequence(String taskId) {
        try {
            Long value = redisson.getAtomicLong(STREAM_PREFIX + taskId + SEQ_SUFFIX)
                    .incrementAndGet();
            return Math.max(0, value - 1);
        } catch (Exception e) {
            log.debug("[EVENT] sequence Redis increment failed taskId={}, use local fallback: {}",
                    taskId, e.getMessage());
            return localSeqFallback.computeIfAbsent(taskId, ignored -> new AtomicLong()).getAndIncrement();
        }
    }

    private long parseSequenceCursor(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return -1;
        return parseSequence(lastEventId);
    }

    private long parseSequence(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String serializePayload(AgentEvent event) {
        try {
            return objectMapper.writeValueAsString(event.payload() == null ? Map.of() : event.payload());
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> deserializePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean isTerminal(TaskEventType type) {
        return type == TaskEventType.TASK_COMPLETED
                || type == TaskEventType.TASK_FAILED
                || type == TaskEventType.TASK_CANCELLED;
    }

    private String streamKey(String taskId) {
        return STREAM_PREFIX + taskId + STREAM_SUFFIX;
    }
}
