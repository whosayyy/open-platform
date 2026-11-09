package com.yin.ai.platform.workflow.runtime;

import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.StepRun;
import com.yin.ai.platform.workflow.model.TaskRun;

import java.util.List;

/**
 * 工作流任务存储 — 保存任务/步骤/产物的事实状态。
 *
 * <p>数据库保存事实状态（接口语义），Redis 实现作为可运行的最小闭环快照；
 * 生产环境可替换为 MySQL 实现，接口不变。
 */
public interface WorkflowTaskStore {

    void saveTaskRun(TaskRun taskRun);

    void updateTaskRun(TaskRun taskRun);

    TaskRun loadTaskRun(String taskRunId);

    void saveStepRun(StepRun stepRun);

    void updateStepRun(StepRun stepRun);

    List<StepRun> listStepRuns(String taskRunId);

    void saveArtifact(Artifact artifact);

    List<Artifact> listArtifacts(String taskRunId);

    Artifact loadArtifact(String taskRunId, ArtifactType type);

    void cleanup(String taskRunId);
}
