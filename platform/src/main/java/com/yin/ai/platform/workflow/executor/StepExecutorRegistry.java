package com.yin.ai.platform.workflow.executor;

import com.yin.ai.platform.workflow.model.ExecutorType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 步骤执行器注册表 — 按 ExecutorType 解析对应执行器。
 */
@Component
public class StepExecutorRegistry {

    private final Map<ExecutorType, StepExecutor> executors = new EnumMap<>(ExecutorType.class);

    public StepExecutorRegistry(List<StepExecutor> executors) {
        for (StepExecutor executor : executors) {
            if (executor != null && executor.type() != null) {
                this.executors.put(executor.type(), executor);
            }
        }
    }

    public StepExecutor get(ExecutorType type) {
        StepExecutor executor = executors.get(type);
        if (executor == null) {
            throw new IllegalStateException("no StepExecutor registered for type=" + type);
        }
        return executor;
    }

    public boolean supports(ExecutorType type) {
        return executors.containsKey(type);
    }
}
