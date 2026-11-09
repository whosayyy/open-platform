package com.yin.ai.platform.workflow.executor;

import com.yin.ai.platform.workflow.model.ArtifactType;

/**
 * 结构化输出契约提供者 — 将产物类型映射为 capture-only 工具 Schema。
 *
 * <p>由业务域（如写作）实现，LlmStepExecutor 据此调用模型。
 */
public interface StructuredOutputSpecProvider {

    /**
     * 返回指定产物类型的结构化输出契约；不支持的返回 null。
     */
    StructuredOutputSpec specFor(ArtifactType type);
}
