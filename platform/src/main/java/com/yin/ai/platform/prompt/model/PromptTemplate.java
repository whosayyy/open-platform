package com.yin.ai.platform.prompt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立 Prompt 模板 — 不属于任何 Skill，用于非 Skill 场景的直接 LLM 调用。
 * <p>
 * 存储在 prompts/templates/standalone/*.yaml。
 * 典型场景：管理后台批量分类、过渡期 V1 兼容。
 */
@Data
public class PromptTemplate {

    /** 唯一名称 */
    private String name;

    /** 语义化版本号 */
    private String version;

    /** 一句话描述 */
    private String description;

    /** 推荐模型 */
    private String model;

    /** 推荐温度 */
    private Double temperature;

    /** 推荐 max_tokens */
    private Integer maxTokens;

    /** Handlebars 模板内容 */
    private String template;

    /** 引用的变量列表 */
    private List<PromptFragment.VariableRef> variables = new ArrayList<>();

    /** 变更历史 */
    private List<PromptVersion> changelog = new ArrayList<>();
}
