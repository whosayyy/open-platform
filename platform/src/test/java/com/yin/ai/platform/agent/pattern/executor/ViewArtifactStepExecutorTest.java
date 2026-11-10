package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.agent.task.ContextStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 {@link ViewArtifactStepExecutor}：
 * 把依赖步骤的 {@code step.{dep}.output} 变量拼接回读给最终编辑步骤；
 * 无依赖输出时回退 artifactKey 占位，二者皆无则失败。
 */
@DisplayName("ViewArtifactStepExecutor")
class ViewArtifactStepExecutorTest {

    private AgentExecutionContext ctxWithPlan(TaskStep current, List<TaskStep> all) {
        AgentExecutionContext ctx = new AgentExecutionContext("t1", "s1", "u1", AgentMode.PLAN_EXECUTE,
                null, mock(EventPublisher.class), mock(ContextStore.class));
        ctx.setPlan(new TaskPlan("写文章", all));
        ctx.setCurrentStep(current.id(), 0);
        return ctx;
    }

    @Test
    @DisplayName("拼接依赖步骤的完整输出")
    void concatenatesDependencyOutputs() {
        TaskStep draft = TaskStep.pending("draft", "起草", "llm", List.of());
        TaskStep finalize = TaskStep.pending("finalize", "定稿", "view_artifact",
                List.of("draft", "outline"));
        AgentExecutionContext ctx = ctxWithPlan(finalize, List.of(draft, finalize));
        ctx.putVariable("step.draft.output", "这是草稿全文");
        ctx.putVariable("step.outline.output", "大纲内容");

        StepExecutorResult result = new ViewArtifactStepExecutor()
                .execute("view_artifact", Map.of(), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("=== 产物：draft ===")
                .contains("这是草稿全文")
                .contains("=== 产物：outline ===")
                .contains("大纲内容");
    }

    @Test
    @DisplayName("无依赖输出时回退 artifactKey 占位")
    void artifactKeyFallbackWhenNoDependencyOutput() {
        TaskStep finalize = TaskStep.pending("finalize", "定稿", "view_artifact", List.of());
        AgentExecutionContext ctx = ctxWithPlan(finalize, List.of(finalize));

        StepExecutorResult result = new ViewArtifactStepExecutor()
                .execute("view_artifact", Map.of("artifactKey", "oss://bucket/art1"), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("[artifact:oss://bucket/art1]");
    }

    @Test
    @DisplayName("既无依赖输出也无 artifactKey → 失败")
    void failsWhenNothingReadable() {
        TaskStep finalize = TaskStep.pending("finalize", "定稿", "view_artifact", List.of());
        AgentExecutionContext ctx = ctxWithPlan(finalize, List.of(finalize));

        StepExecutorResult result = new ViewArtifactStepExecutor()
                .execute("view_artifact", Map.of(), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("no readable dependency output");
    }

    @Test
    @DisplayName("上下文无计划 → 失败")
    void failsWhenNoPlan() {
        AgentExecutionContext ctx = new AgentExecutionContext("t1", "s1", "u1", AgentMode.PLAN_EXECUTE,
                null, mock(EventPublisher.class), mock(ContextStore.class));

        StepExecutorResult result = new ViewArtifactStepExecutor()
                .execute("view_artifact", Map.of(), ctx);

        assertThat(result.success()).isFalse();
    }
}
