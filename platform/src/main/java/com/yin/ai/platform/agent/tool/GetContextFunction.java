package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.task.TaskSnapshot;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.function.*;

import java.util.List;
import java.util.Map;

/**
 * get_context — 读取上下文变量。
 */
public class GetContextFunction extends AbstractFunction {

    private final TaskEngine taskEngine;

    public GetContextFunction(TaskEngine taskEngine) {
        this.taskEngine = taskEngine;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("get_context")
                .description("读取任务上下文中的变量值。用于获取前序步骤保存的中间结果。")
                .category("agent-control")
                .risk(FunctionDefinition.Risk.READ_ONLY)
                .callableByLLM(true)
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder()
                                .name("key").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("变量名").build()
                ))
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        Map<String, Object> params = input.getParameters();
        String taskId = AgentExecutionContextHolder.currentTaskId();
        String key = (String) params.get("key");

        TaskSnapshot snapshot = taskEngine.getSnapshot(taskId);
        if (snapshot == null || snapshot.variables() == null) {
            return FunctionResult.ok(Map.of("key", key, "value", "", "found", false));
        }

        Object value = snapshot.variables().get(key);
        return FunctionResult.ok(Map.of(
                "key", key,
                "value", value != null ? value.toString() : "",
                "found", value != null));
    }
}
