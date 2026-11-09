package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.agent.task.TaskSnapshot;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.function.*;

import java.util.List;
import java.util.Map;

/**
 * get_next_step — 获取下一个待执行步骤。
 */
public class GetNextStepFunction extends AbstractFunction {

    private final TaskEngine taskEngine;

    public GetNextStepFunction(TaskEngine taskEngine) {
        this.taskEngine = taskEngine;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("get_next_step")
                .description("获取下一个待执行的步骤。如果所有步骤已完成，返回 ALL_DONE。")
                .category("agent-control")
                .risk(FunctionDefinition.Risk.READ_ONLY)
                .callableByLLM(true)
                .parameters(List.of())
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        String taskId = AgentExecutionContextHolder.currentTaskId();
        TaskSnapshot snapshot = taskEngine.getSnapshot(taskId);

        if (snapshot == null || snapshot.plan() == null) {
            return FunctionResult.ok(Map.of("status", "NO_PLAN", "message", "尚未创建执行计划"));
        }

        TaskStep next = snapshot.plan().nextPending();
        if (next == null) {
            return FunctionResult.ok(Map.of(
                    "status", "ALL_DONE",
                    "message", "所有步骤已完成，请调用 append_artifact 保存最终产物"));
        }

        return FunctionResult.ok(Map.of(
                "status", "HAS_NEXT",
                "stepId", next.id(),
                "description", next.description(),
                "executor", next.executor(),
                "dependsOn", next.dependsOn()
        ));
    }
}
