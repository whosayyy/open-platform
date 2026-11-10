package com.yin.ai.platform.workflow.load;

import com.yin.ai.platform.workflow.model.WorkflowDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 工作流定义加载 SPI — 从文件/配置中心加载并反序列化。
 */
public interface WorkflowLoader {

    /**
     * 按名称加载工作流。
     */
    Optional<WorkflowDefinition> load(String workflowName);

    /**
     * 加载全部工作流。
     */
    List<WorkflowDefinition> loadAll();
}
