package com.yin.ai.platform.prompt;

import com.yin.ai.platform.prompt.model.PromptFragment;
import com.yin.ai.platform.prompt.model.PromptTemplate;
import com.yin.ai.platform.prompt.model.PromptVersion;
import com.yin.ai.platform.prompt.model.VariableDef;

import java.util.List;
import java.util.Optional;

/**
 * Prompt Center Facade — Skill Runtime 和 Application 层通过此接口管理 Prompt。
 * <p>
 * 职责划分（按 08-Prompt-Center.md §4.1）：
 * <ul>
 *   <li>Skill .md 文件 → System Prompt 的"主体"（角色定义、核心规则、工具说明）</li>
 *   <li>Prompt Center → 管理"可复用片段"（Fragment）、"独立 Prompt"（Standalone）、变量定义</li>
 * </ul>
 * 两者通过 {@code {{> fragmentName}}} 桥接。
 */
public interface PromptCenter {

    // —— Fragment 管理 ——

    Optional<PromptFragment> getFragment(String name, String version);

    Optional<PromptFragment> getLatestFragment(String name);

    List<PromptFragment> listFragments();

    // —— Template 管理 ——

    Optional<PromptTemplate> getTemplate(String name, String version);

    Optional<PromptTemplate> getLatestTemplate(String name);

    List<PromptTemplate> listTemplates();

    // —— Variable 管理 ——

    Optional<VariableDef> getVariable(String name);

    List<VariableDef> listVariables();

    // —— 缓存管理 ——

    void refreshCache();
}
