package com.yin.ai.platform.workflow.prompt;

import java.util.Optional;

/**
 * Stage Prompt 注册表 — 按引用加载阶段提示词（如 "writing/brief"）。
 */
public interface StagePromptRegistry {

    /**
     * 按引用加载提示词模板。
     */
    Optional<String> load(String promptRef);

    /**
     * 提示词是否存在。
     */
    default boolean exists(String promptRef) {
        return promptRef != null && load(promptRef).isPresent();
    }
}
