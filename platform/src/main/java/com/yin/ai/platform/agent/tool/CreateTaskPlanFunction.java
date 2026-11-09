package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.pattern.*;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.function.*;

import java.util.List;
import java.util.Map;

/**
 * create_task_plan — Planner 创建执行计划。
 * LLM 通过 Tool Calling 调用，将复杂目标拆解为有序步骤。
 */
public class CreateTaskPlanFunction extends AbstractFunction {

    private final ContextStore contextStore;

    public CreateTaskPlanFunction(ContextStore contextStore) {
        this.contextStore = contextStore;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("create_task_plan")
                .description("创建任务执行计划。将复杂目标拆解为有序步骤。每个步骤指定 id、描述、执行器（skill/tool/llm）、依赖。")
                .category("agent-control")
                .risk(FunctionDefinition.Risk.WRITE)
                .callableByLLM(true)
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder()
                                .name("goal").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("任务总目标").build(),
                        FunctionDefinition.ParamDef.builder()
                                .name("steps").type(FunctionDefinition.ParamType.ARRAY)
                                .required(true).description("步骤列表，每步含 id/description/executor/dependsOn").build()
                ))
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected FunctionResult doExecute(FunctionInput input) {
        Map<String, Object> params = input.getParameters();
        String goal = (String) params.get("goal");
        List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) params.get("steps");

        List<TaskStep> steps = rawSteps.stream()
                .map(s -> TaskStep.pending(
                        (String) s.get("id"),
                        (String) s.get("description"),
                        (String) s.getOrDefault("executor", "llm"),
                        (List<String>) s.getOrDefault("dependsOn", List.of())
                ))
                .toList();

        TaskPlan plan = new TaskPlan(goal, steps);
        String taskId = AgentExecutionContextHolder.currentTaskId();
        contextStore.savePlanAsync(taskId, plan);

        return FunctionResult.ok(Map.of(
                "planCreated", true,
                "goal", goal,
                "stepCount", steps.size(),
                "steps", steps.stream().map(s -> s.id() + ": " + s.description()).toList()
        ));
    }
}
