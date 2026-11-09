package com.yin.ai.platform.gateway.stream;

import com.yin.ai.platform.config.PipelineConfig;
import com.yin.ai.platform.engine.EngineProperties;
import com.yin.ai.platform.engine.TaskManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SinkStreamProducerTest {

    @Test
    void keepsTaskSinkAliveWhenSubscriberDisconnects() {
        SinkStreamProducer producer = new SinkStreamProducer(
                new PipelineConfig(),
                mock(TaskManager.class),
                new StreamMetrics(),
                new EngineProperties());
        String taskId = "task-reconnect";
        producer.initialize(taskId);

        reactor.core.Disposable subscription = producer.asFlux(taskId).subscribe();
        subscription.dispose();

        assertThat(producer.hasActiveSink(taskId)).isTrue();
        producer.emitThinking(taskId, "still-running");
        assertThat(producer.asFlux(taskId).take(1).blockFirst())
                .extracting(StreamPacket::getContent)
                .isEqualTo("still-running");
        producer.complete(taskId);
    }

    @Test
    void preservesDurableSequenceAndContinuesFromIt() {
        SinkStreamProducer producer = new SinkStreamProducer(
                new PipelineConfig(),
                mock(TaskManager.class),
                new StreamMetrics(),
                new EngineProperties());
        String taskId = "task-sequence";
        producer.initialize(taskId);

        producer.emitPacket(taskId, StreamPacket.builder()
                .type("task.started")
                .seq(7)
                .ts(123L)
                .build());
        producer.emitThinking(taskId, "next");

        List<StreamPacket> packets = producer.asFlux(taskId)
                .take(2)
                .collectList()
                .block();

        assertThat(packets).isNotNull();
        assertThat(packets)
                .extracting(StreamPacket::getSeq)
                .containsExactly(7L, 8L);
        producer.complete(taskId);
    }
}
