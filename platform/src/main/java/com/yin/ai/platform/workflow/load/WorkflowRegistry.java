package com.yin.ai.platform.workflow.load;

import com.yin.ai.platform.workflow.model.WorkflowDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 工作流定义注册表 — 启动时加载并校验全部工作流定义。
 */
public interface WorkflowRegistry {

    /**
     * 按名称获取工作流；不存在抛 IllegalStateException。
     */
    WorkflowDefinition get(String name);

    /**
     * 按名称查询工作流。
     */
    Optional<WorkflowDefinition> find(String name);

    /**
     * 全部已注册工作流。
     */
    List<WorkflowDefinition> all();
}
