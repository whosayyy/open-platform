package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.policy.AgentStepLimitExceededException;
import com.yin.ai.platform.agent.task.ContextStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.yin.ai.platform.core.agent.mode.AgentMode;

class AgentExecutionContextStepLimitTest {

    @Test
    void rejectsPlansThatExceedEffectiveStepLimit() {
        AgentExecutionContext context = new AgentExecutionContext(
                "task-1", "session-1", "42", AgentMode.PLAN_EXECUTE,
                mock(AgentContext.class), mock(EventPublisher.class), mock(ContextStore.class));
        context.putVariable("agent.maxSteps", 2);
        TaskPlan plan = new TaskPlan("goal", List.of(
                TaskStep.pending("one", "one", "llm", List.of()),
                TaskStep.pending("two", "two", "llm", List.of("one")),
                TaskStep.pending("three", "three", "llm", List.of("two"))));

        assertThatThrownBy(() -> context.setPlan(plan))
                .isInstanceOf(AgentStepLimitExceededException.class)
                .hasMessage("AGENT_MAX_STEPS_EXCEEDED: planned=3, limit=2");
    }
}
