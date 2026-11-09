package com.yin.ai.platform.core.skill;

import lombok.Data;

import java.util.List;

/**
 * Skill 清单 — 从 .md 文件的 YAML Frontmatter 解析而来。
 * <p>
 * 每个字段对应 .md 文件 YAML Frontmatter 中的一个配置项。
 * Markdown 正文通过 {@link #systemPromptTemplate} 持有，
 * 由外层 Prompt 组合器注入运行时变量。
 * <p>
 * V2.1 新增 category / priority / enabled / dependencies / tags / variables，
 * 用于支持多 Skill 选择、依赖解析和 Prompt 动态组合。
 */
@Data
public class SkillManifest {

    /** 全局唯一标识 */
    private String name;

    /** 语义化版本号 */
    private String version;

    /** 一句话描述 */
    private String description;

    /** 绑定的意图枚举值 */
    private String intent;

    /** 分类：base / specialized / utility */
    private String category;

    /** Prompt 组合排序权重（越大越靠前），默认 0 */
    private int priority;

    /** 是否启用，false 时 SkillLoader 跳过加载 */
    private boolean enabled = true;

    /** 本 Skill 依赖的其他 Skill 名称列表 */
    private List<String> dependencies = List.of();

    /** 标签，RuleSkillSelector 匹配使用 */
    private List<String> tags = List.of();

    /** 引用的 Prompt Fragment 列表（name@version 格式，如 "safety-rules@2.0"） */
    private List<String> fragments = List.of();

    /** 本 Skill 期望的模板变量名列表 */
    private List<String> variables = List.of();

    /** 覆盖默认模型（不填使用运行时默认值） */
    private String model;

    /** 覆盖默认温度 */
    private Double temperature;

    /** 覆盖默认 max_tokens */
    private Integer maxTokens;

    /** 是否默认流式 */
    private Boolean stream;

    /** 允许的工具名称列表 */
    private List<String> tools;

    /** 关联的知识库名称列表（替代旧的 rag 字段） */
    private List<String> knowledge = List.of();

    /** 行为规则 */
    private RulesConfig rules;

    /** 记忆配置 */
    private MemoryConfig memory;

    /** 解析后的 Markdown 正文（System Prompt 模板） */
    private String systemPromptTemplate;

    @Data
    public static class RulesConfig {
        private int maxToolCallTurns = 5;
        private int maxAgentSteps = 12;
        private boolean parallelToolExecution = true;
        private int maxParallelTools = 3;
        private boolean sensitiveWordFilter = true;
        private String fallbackMessage = "AI 服务暂时不可用，请稍后重试";
        private String toolNotFoundMessage = "当前问题无法回答，请换一个问题吧";
    }

    @Data
    public static class MemoryConfig {
        private boolean enabled = true;
        private int sessionTtlSeconds = 1800;
        private int maxHistoryMessages = 20;
    }
}
