package com.yin.ai.platform.workflow.runtime;

import com.yin.ai.platform.workflow.model.StepRun;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;

import java.util.List;

/**
 * 步骤调度器 — 负责 READY Step 查找与下游 Step 激活（依赖满足后自动就绪）。
 *
 * <p>不含任何 LLM 流程控制逻辑，状态迁移由 Runtime 统一维护。
 */
public interface StepScheduler {

    /**
     * 初始化任务：将无依赖的步骤置为 READY（或按条件 SKIPPED），并持久化。
     */
    List<StepRun> initialize(TaskRun task, WorkflowDefinition workflow, WorkflowTaskStore store);

    /**
     * 步骤成功后激活其下游依赖已全部满足的步骤。
     */
    List<StepRun> activateDownstream(TaskRun task, WorkflowDefinition workflow,
                                     StepRun completedStep, WorkflowTaskStore store);

    /**
     * 找出下一个可执行的 READY 步骤（按工作流顺序优先）。
     */
    StepRun findNextReadyStep(TaskRun task, WorkflowDefinition workflow, WorkflowTaskStore store);
}
