package com.yin.ai.platform.workflow.runtime;

import com.yin.ai.platform.workflow.model.StepCondition;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.StepRun;
import com.yin.ai.platform.workflow.model.StepStatus;
import com.yin.ai.platform.workflow.model.TaskRun;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认步骤调度器 — 拓扑感知的依赖激活。
 *
 * <p>SKIPPED 视为依赖已满足（如发布步骤因未显式要求发布而跳过，不影响任务完成）。
 */
@Slf4j
@Component
public class DefaultStepScheduler implements StepScheduler {

    @Override
    public List<StepRun> initialize(TaskRun task, WorkflowDefinition workflow, WorkflowTaskStore store) {
        List<StepRun> activated = new ArrayList<>();
        for (StepDefinition step : workflow.getSteps()) {
            if (step.getDependsOn() == null || step.getDependsOn().isEmpty()) {
                activateStep(task, workflow, step, store, activated);
            }
        }
        return activated;
    }

    @Override
    public List<StepRun> activateDownstream(TaskRun task, WorkflowDefinition workflow,
                                            StepRun completedStep, WorkflowTaskStore store) {
        List<StepRun> activated = new ArrayList<>();
        for (StepDefinition step : workflow.getSteps()) {
            if (step.getDependsOn() != null && step.getDependsOn().contains(completedStep.getStepId())) {
                if (allDependenciesDone(task, workflow, step, store)) {
                    activateStep(task, workflow, step, store, activated);
                }
            }
        }
        return activated;
    }

    @Override
    public StepRun findNextReadyStep(TaskRun task, WorkflowDefinition workflow, WorkflowTaskStore store) {
        List<StepRun> runs = store.listStepRuns(task.getTaskRunId());
        // 按工作流定义顺序（拓扑序）优先，保证确定性
        for (StepDefinition step : workflow.orderedSteps()) {
            for (StepRun run : runs) {
                if (run.getStepId().equals(step.getId()) && run.getStatus() == StepStatus.READY) {
                    return run;
                }
            }
        }
        return null;
    }

    private void activateStep(TaskRun task, WorkflowDefinition workflow, StepDefinition step,
                              WorkflowTaskStore store, List<StepRun> activated) {
        StepRun run = StepRun.of(task.getTaskRunId(), step);
        if (evaluateCondition(step.getCondition(), task)) {
            run.setStatus(StepStatus.READY);
        } else {
            run.setStatus(StepStatus.SKIPPED);
            run.setDurationMs(0L);
        }
        store.saveStepRun(run);
        activated.add(run);
        if (run.getStatus() == StepStatus.SKIPPED) {
            log.info("[WF-SCHED] step {} skipped taskRunId={}", step.getId(), task.getTaskRunId());
            activated.addAll(activateDownstream(task, workflow, run, store));
        }
    }

    private boolean allDependenciesDone(TaskRun task, WorkflowDefinition workflow,
                                        StepDefinition step, WorkflowTaskStore store) {
        List<StepRun> runs = store.listStepRuns(task.getTaskRunId());
        for (String depId : step.getDependsOn()) {
            boolean done = false;
            for (StepRun run : runs) {
                if (run.getStepId().equals(depId)
                        && (run.getStatus() == StepStatus.SUCCEEDED || run.getStatus() == StepStatus.SKIPPED)) {
                    done = true;
                    break;
                }
            }
            if (!done) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateCondition(StepCondition condition, TaskRun task) {
        if (condition == null) {
            return true;
        }
        if (condition.isExplicitPublish()) {
            return task.isExplicitPublishRequested();
        }
        // 未知条件类型默认放行，避免阻塞工作流
        return true;
    }
}
