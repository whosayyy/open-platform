package com.yin.ai.platform.engine.stream;

import com.yin.ai.platform.config.PipelineConfig;
import com.yin.ai.platform.engine.EngineProperties;
import com.yin.ai.platform.engine.TaskCanceller;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SinkStreamProducerTest {

    /**
     * 录制型取消器：替代 Mockito。
     * <p>
     * 之前的版本 mock 了 {@code TaskManager} 具体类，会触发 Mockito 的 inline mock maker，
     * 而后者需要自附加 ByteBuddy agent，在中文路径环境下会失败。改用窄接口后这里只需要一个
     * 简单实现，测试也不再依赖字节码插桩。
     */
    private static final class RecordingCanceller implements TaskCanceller {

        private final List<String> cancelled = new ArrayList<>();

        @Override
        public void cancel(String taskId) {
            cancelled.add(taskId);
        }
    }

    @Test
    void keepsTaskSinkAliveWhenSubscriberDisconnects() {
        SinkStreamProducer producer = new SinkStreamProducer(
                new PipelineConfig(),
                new RecordingCanceller(),
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
                new RecordingCanceller(),
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
