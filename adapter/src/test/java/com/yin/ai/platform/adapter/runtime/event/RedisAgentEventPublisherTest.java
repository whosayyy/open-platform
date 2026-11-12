package com.yin.ai.platform.adapter.runtime.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.TaskEventType;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisAgentEventPublisherTest {

    @Test
    void deliversWithLocalSequenceWhenRedisIsUnavailable() {
        RedisAgentEventPublisher publisher = unavailableRedisPublisher();
        AgentEvent terminal = new AgentEvent(null, "task-1", TaskEventType.TASK_COMPLETED,
                null, Map.of("result", "done"), Instant.now());

        StepVerifier.create(publisher.subscribe("task-1"))
                .then(() -> publisher.publish(terminal))
                .assertNext(event -> {
                    assertThat(event.id()).isEqualTo("0");
                    assertThat(event.taskId()).isEqualTo("task-1");
                    assertThat(event.type()).isEqualTo(TaskEventType.TASK_COMPLETED);
                    assertThat(event.payload()).containsEntry("result", "done");
                })
                .verifyComplete();
    }

    @Test
    void assignsMonotonicLocalSequencesAcrossEvents() {
        RedisAgentEventPublisher publisher = unavailableRedisPublisher();

        StepVerifier.create(publisher.subscribe("task-2").take(2))
                .then(() -> publisher.publish(AgentEvent.taskStarted("task-2")))
                .then(() -> publisher.publish(AgentEvent.progress("task-2", 1, 2, "working")))
                .expectNextMatches(event -> "0".equals(event.id()))
                .expectNextMatches(event -> "1".equals(event.id()))
                .verifyComplete();
    }

    private static RedisAgentEventPublisher unavailableRedisPublisher() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getAtomicLong(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        when(redisson.getStream(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        return new RedisAgentEventPublisher(redisson, new ObjectMapper());
    }
}
