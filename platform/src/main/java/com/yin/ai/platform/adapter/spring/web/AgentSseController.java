package com.yin.ai.platform.adapter.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.adapter.runtime.event.RedisAgentEventPublisher;
import com.yin.ai.platform.agent.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/** Spring Web adapter that exposes persisted Agent Runtime events over SSE. */
@RestController
@RequestMapping("/api/agent/tasks")
@ConditionalOnBean(RedisAgentEventPublisher.class)
public class AgentSseController {

    private static final Logger log = LoggerFactory.getLogger(AgentSseController.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final RedisAgentEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AgentSseController(RedisAgentEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@taskAccessGuard.canAccess(#taskId)")
    public Flux<ServerSentEvent<String>> streamEvents(
            @PathVariable String taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        log.info("[SSE] subscribe taskId={} lastEventId={}", taskId, lastEventId);
        Flux<AgentEvent> events = eventPublisher.subscribeWithReplay(taskId, lastEventId);
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(ignored -> ServerSentEvent.<String>builder()
                        .event("HEARTBEAT")
                        .data("")
                        .build());
        Flux<ServerSentEvent<String>> eventStream = events.map(this::toSse);

        return Flux.merge(eventStream, heartbeat)
                .takeUntil(event -> event.event() != null && isTerminal(event.event()))
                .doFinally(signal -> log.info("[SSE] disconnected taskId={} signal={}", taskId, signal));
    }

    private ServerSentEvent<String> toSse(AgentEvent event) {
        return ServerSentEvent.<String>builder()
                .id(event.id() != null ? event.id() : String.valueOf(System.currentTimeMillis()))
                .event(event.type().name())
                .data(serializeEvent(event))
                .build();
    }

    private String serializeEvent(AgentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"eventType\":\"error\",\"payload\":{\"errorCode\":\"EVENT_SERIALIZATION_FAILED\",\"retryable\":false}}";
        }
    }

    private boolean isTerminal(String eventType) {
        return "TASK_COMPLETED".equals(eventType)
                || "TASK_FAILED".equals(eventType)
                || "TASK_CANCELLED".equals(eventType);
    }
}
