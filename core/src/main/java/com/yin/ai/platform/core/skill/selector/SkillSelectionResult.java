package com.yin.ai.platform.core.skill.selector;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Skill 选择结果 — 包含选中的 Skill 名称列表及元数据。
 */
@Data
@Builder
public class SkillSelectionResult {

    /** 选中的 Skill 名称列表（按优先级排序） */
    private List<String> skillNames;

    /** 产生此结果的选择器名称 */
    private String selectorName;

    /** 选择置信度 [0.0, 1.0] */
    @Builder.Default
    private double confidence = 1.0;

    /** 额外上下文，传递给下游依赖解析和 Prompt 组合组件 */
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    public static SkillSelectionResult of(List<String> skillNames, String selectorName) {
        return SkillSelectionResult.builder()
                .skillNames(skillNames)
                .selectorName(selectorName)
                .build();
    }
}
