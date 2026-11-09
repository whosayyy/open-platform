package com.yin.ai.platform.workflow.model;

/**
 * 工作流产物类型 — 步骤之间传递的结构化数据。
 *
 * <p>每个步骤声明输入与输出 Artifact，运行时只向当前步骤注入它声明的输入，
 * 不注入完整聊天历史或无关工具结果。
 */
public enum ArtifactType {

    /** 用户原始输入（resume 时注入的补充信息） */
    RAW_USER_INPUT,

    WRITING_BRIEF,
    EVIDENCE_SET,
    ARTICLE_THESIS,
    ARTICLE_OUTLINE,
    ARTICLE_DRAFT,
    EDITORIAL_REVIEW,
    FINAL_ARTICLE,
    SAVED_DRAFT,
    PUBLISHED_ARTICLE,

    /** 最终返回给调用方的结果 */
    FINAL_RESULT;

    /**
     * 宽松解析（大小写不敏感）。解析失败抛出 IllegalArgumentException，
     * 由 WorkflowValidator 在校验阶段捕获并给出清晰错误。
     */
    public static ArtifactType from(String name) {
        for (ArtifactType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown artifact type: " + name);
    }
}
