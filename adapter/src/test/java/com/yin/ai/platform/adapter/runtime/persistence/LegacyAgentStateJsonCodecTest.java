package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.agent.pattern.ContextSnapshot;
import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.agent.task.TaskSnapshot;
import com.yin.ai.platform.agent.task.TaskState;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAgentStateJsonCodecTest {

    @Test
    void roundTripsTaskAndContextSnapshotsWithoutKryoClassIds() throws Exception {
        TaskPlan plan = new TaskPlan("write", List.of(
                TaskStep.pending("research", "检索资料", "web_search", List.of()).withOutput("done"),
                TaskStep.pending("draft", "撰写初稿", "llm", List.of("research"))));
        Instant now = Instant.now();
        TaskSnapshot task = new TaskSnapshot("task-1", "write", "1", "session-1",
                AgentMode.PLAN_EXECUTE, TaskState.FAILED, plan, 1,
                Map.of("model", "deepseek-chat"), now, now, "redis");
        ContextSnapshot context = new ContextSnapshot("task-1", "session-1", "1",
                AgentMode.PLAN_EXECUTE, TaskState.FAILED, "draft", 1,
                Map.of("step.research.output", "done"), List.of(), List.of(), plan, now, now);

        assertThat(roundTrip(task)).isInstanceOf(TaskSnapshot.class)
                .extracting(value -> ((TaskSnapshot) value).plan().completedCount())
                .isEqualTo(1L);
        assertThat(roundTrip(context)).isInstanceOf(ContextSnapshot.class)
                .extracting(value -> ((ContextSnapshot) value).currentStepId())
                .isEqualTo("draft");
    }

    private Object roundTrip(Object value) throws Exception {
        ByteBuf encoded = LegacyAgentStateJsonCodec.INSTANCE.getValueEncoder().encode(value);
        try {
            return LegacyAgentStateJsonCodec.INSTANCE.getValueDecoder().decode(encoded, null);
        } finally {
            encoded.release();
        }
    }
}
