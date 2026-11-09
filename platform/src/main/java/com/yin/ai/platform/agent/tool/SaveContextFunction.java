package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.function.*;

import java.util.List;
import java.util.Map;

/**
 * save_context — 保存中间结果到上下文。
 */
public class SaveContextFunction extends AbstractFunction {

    private final ContextStore contextStore;

    public SaveContextFunction(ContextStore contextStore) {
        this.contextStore = contextStore;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("save_context")
                .description("保存中间结果到任务上下文。后续步骤可通过 get_context 读取。")
                .category("agent-control")
                .risk(FunctionDefinition.Risk.WRITE)
                .callableByLLM(true)
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder()
                                .name("key").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("变量名").build(),
                        FunctionDefinition.ParamDef.builder()
                                .name("value").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("变量值").build()
                ))
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        Map<String, Object> params = input.getParameters();
        String taskId = AgentExecutionContextHolder.currentTaskId();
        String key = (String) params.get("key");
        Object value = params.get("value");

        contextStore.saveVariableAsync(taskId, key, value);
        return FunctionResult.ok(Map.of("saved", true, "key", key));
    }
}
