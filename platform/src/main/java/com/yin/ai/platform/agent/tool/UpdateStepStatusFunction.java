package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.pattern.StepStatus;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.agent.task.TaskStepResult;
import com.yin.ai.platform.function.*;

import java.util.List;
import java.util.Map;

/**
 * update_step_status — 更新步骤执行状态。
 */
public class UpdateStepStatusFunction extends AbstractFunction {

    private final TaskEngine taskEngine;
    private final EventPublisher eventPublisher;

    public UpdateStepStatusFunction(TaskEngine taskEngine, EventPublisher eventPublisher) {
        this.taskEngine = taskEngine;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("update_step_status")
                .description("更新任务步骤的执行状态。在完成一个步骤后调用。")
                .category("agent-control")
                .risk(FunctionDefinition.Risk.WRITE)
                .callableByLLM(true)
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder()
                                .name("stepId").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("步骤 ID").build(),
                        FunctionDefinition.ParamDef.builder()
                                .name("status").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("状态：RUNNING/COMPLETED/FAILED/SKIPPED").build(),
                        FunctionDefinition.ParamDef.builder()
                                .name("output").type(FunctionDefinition.ParamType.STRING)
                                .required(false).description("步骤执行结果摘要").build(),
                        FunctionDefinition.ParamDef.builder()
                                .name("error").type(FunctionDefinition.ParamType.STRING)
                                .required(false).description("失败时的错误信息").build()
                ))
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        Map<String, Object> params = input.getParameters();
        String taskId = AgentExecutionContextHolder.currentTaskId();
        String stepId = (String) params.get("stepId");
        String statusStr = (String) params.get("status");
        String output = (String) params.get("output");
        String error = (String) params.get("error");

        StepStatus status = StepStatus.valueOf(statusStr);
        TaskStepResult result = status == StepStatus.COMPLETED
                ? TaskStepResult.success(stepId, output, 0, 0)
                : TaskStepResult.failure(stepId, error, 0);

        taskEngine.saveStepResult(taskId, stepId, result);

        if (status == StepStatus.COMPLETED) {
            eventPublisher.publish(AgentEvent.stepCompleted(taskId, stepId, output));
        } else if (status == StepStatus.FAILED) {
            eventPublisher.publish(AgentEvent.stepFailed(taskId, stepId, error));
        }

        return FunctionResult.ok(Map.of("stepId", stepId, "status", status.name(), "updated", true));
    }
}
