package com.yin.ai.platform.adapter.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yin.ai.platform.adapter.runtime.event.RedisAgentEventPublisher;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.TaskEventType;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSseControllerTest {

    @Test
    void mapsTerminalRuntimeEventToExistingSseContract() {
        RedisAgentEventPublisher publisher = mock(RedisAgentEventPublisher.class);
        AgentEvent completed = new AgentEvent("7", "task-1", TaskEventType.TASK_COMPLETED,
                null, Map.of("result", "done"), Instant.parse("2026-08-24T10:00:00Z"));
        when(publisher.subscribeWithReplay("task-1", "6")).thenReturn(Flux.just(completed));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AgentSseController controller = new AgentSseController(publisher, objectMapper);

        StepVerifier.create(controller.streamEvents("task-1", "6"))
                .assertNext(event -> assertSseContract(event, completed))
                .verifyComplete();
    }

    private static void assertSseContract(ServerSentEvent<String> event, AgentEvent source) {
        assertThat(event.id()).isEqualTo(source.id());
        assertThat(event.event()).isEqualTo("TASK_COMPLETED");
        assertThat(event.data()).contains("\"taskId\":\"task-1\"")
                .contains("\"result\":\"done\"");
    }
}
