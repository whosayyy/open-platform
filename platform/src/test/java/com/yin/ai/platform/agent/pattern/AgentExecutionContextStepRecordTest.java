package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.gateway.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.yin.ai.platform.core.agent.mode.AgentMode;

class AgentExecutionContextStepRecordTest {

    private AgentExecutionContext newContext() {
        return new AgentExecutionContext("task-1", "session-1", "42", AgentMode.PLAN_EXECUTE,
                mock(AgentContext.class), mock(EventPublisher.class), mock(ContextStore.class));
    }

    private StepRecord sampleRecord() {
        TaskStep step = TaskStep.pending("search", "广度搜索", "web_search", java.util.List.of());
        return StepRecord.from("task-1", step, 0, Map.of("keyword", "AI"), null, 1,
                System.currentTimeMillis() - 50);
    }

    @Test
    void recordStepAppendsAndAppearsInSnapshot() {
        AgentExecutionContext ctx = newContext();
        StepRecord record = sampleRecord();

        ctx.recordStep(record);

        assertThat(ctx.getStepRecords()).containsExactly(record);
        assertThat(ctx.snapshot().stepRecords()).containsExactly(record);
    }

    @Test
    void nullRecordIsIgnored() {
        AgentExecutionContext ctx = newContext();

        ctx.recordStep(null);

        assertThat(ctx.getStepRecords()).isEmpty();
    }

    @Test
    void restoreCarriesLedgerFromSnapshot() {
        AgentExecutionContext ctx = newContext();
        StepRecord record = sampleRecord();
        ctx.recordStep(record);

        AgentExecutionContext restored = AgentExecutionContext.restore(ctx.snapshot(),
                mock(AgentContext.class), mock(EventPublisher.class), mock(ContextStore.class));

        assertThat(restored.getTaskId()).isEqualTo("task-1");
        assertThat(restored.getStepRecords()).containsExactly(record);
    }

    @Test
    void accumulatesProviderTokenUsageAcrossCalls() {
        AgentExecutionContext ctx = newContext();

        ctx.accumulateTokenUsage(new TokenUsage(100, 25, 125));
        ctx.accumulateTokenUsage(new TokenUsage(40, 10, null));

        assertThat(ctx.getVariable("usage.promptTokens")).isEqualTo(140);
        assertThat(ctx.getVariable("usage.completionTokens")).isEqualTo(35);
        assertThat(ctx.getVariable("usage.totalTokens")).isEqualTo(175);
    }

    @Test
    void preservesInitialSemanticStepsAndConsolidatesDynamicSearchActivities() {
        AgentExecutionContext ctx = newContext();
        TaskStep research = TaskStep.pending("research", "调研热点素材", "web_search", java.util.List.of());
        TaskStep draft = TaskStep.pending("draft", "撰写初稿", "llm", java.util.List.of("research"));
        ctx.setPlan(new TaskPlan("写文章", java.util.List.of(research, draft)));

        TaskStep searchPolicy = TaskStep.pending("search-policy", "web_search 核准政策资料",
                "web_search", java.util.List.of("research"));
        TaskStep searchTrade = TaskStep.pending("search-trade", "web_search 核准经贸资料",
                "web_search", java.util.List.of("research"));
        TaskStep readback = TaskStep.pending("read-draft", "读取完整产物", "view_artifact",
                java.util.List.of("draft"));
        ctx.replacePlan(new TaskPlan("写文章",
                java.util.List.of(research, draft, searchPolicy, searchTrade, readback)), false);

        assertThat(ctx.getUserVisiblePlan().steps())
                .extracting(TaskStep::description)
                .containsExactly("调研热点素材", "撰写初稿", "联网核准资料");
        assertThat(ctx.getUserVisiblePlan().steps())
                .extracting(TaskStep::executor)
                .doesNotContain("view_artifact");
    }
}
