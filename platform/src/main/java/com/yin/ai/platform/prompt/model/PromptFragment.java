package com.yin.ai.platform.prompt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 可复用 Prompt 片段 — 可被多个 Skill 跨引用。
 * <p>
 * 存储在 prompts/fragments/*.yaml，由 {@code YamlPromptStore} 加载。
 * Skill 通过 YAML Frontmatter {@code fragments: [name@version]} 声明引用，
 * {@code PromptComposer} 编译为 Handlebars Partial 后通过 {@code {{> fragmentName}}} 展开。
 */
@Data
public class PromptFragment {

    /** 唯一名称（kebab-case） */
    private String name;

    /** 语义化版本号 */
    private String version;

    /** 一句话描述 */
    private String description;

    /** Handlebars Partial 内容 */
    private String fragment;

    /** 引用的变量列表 */
    private List<VariableRef> variables = new ArrayList<>();

    /** 变更历史 */
    private List<PromptVersion> changelog = new ArrayList<>();

    @Data
    public static class VariableRef {
        private String name;
        private boolean required;
        private String description;
    }
}
