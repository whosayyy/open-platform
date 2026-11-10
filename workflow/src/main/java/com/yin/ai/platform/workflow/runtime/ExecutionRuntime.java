package com.yin.ai.platform.workflow.runtime;

import com.yin.ai.platform.workflow.model.TaskRequest;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.UserInput;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;

/**
 * 工作流执行运行时 — 唯一的流程驱动者。
 *
 * <p>LLM 只负责语言理解与内容生成，绝不驱动流程推进。
 */
public interface ExecutionRuntime {

    /**
     * 创建任务并初始化步骤（无依赖步骤置为 READY）。不阻塞执行。
     */
    TaskRun start(WorkflowDefinition workflow, TaskRequest request);

    /**
     * 运行任务直到完成、失败或等待人工输入。同步执行，调用方决定是否异步。
     */
    void execute(String taskRunId);

    /**
     * WAITING_INPUT 时补充输入并继续执行。
     */
    void resume(String taskRunId, UserInput userInput);

    /**
     * 取消任务。
     */
    void cancel(String taskRunId);

    /**
     * 查询任务当前状态。
     */
    TaskRun getTask(String taskRunId);
}
