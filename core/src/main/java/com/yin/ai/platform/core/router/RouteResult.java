package com.yin.ai.platform.core.router;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 路由最终结果：意图 + 匹配详情。
 * <p>
 * V2.1 移除 {@code handler} 字段，Skill 选择职责转移到 {@code SkillSelector}。
 * {@code skillNames} 由上游（LegacyAdapter）通过 SkillSelector 填充。
 */
@Data
@Builder
public class RouteResult {

    /** 最终意图（业务定义的 String 标识符） */
    private String intent;

    /** 匹配详情 */
    private IntentMatchResult matchResult;

    /** 是否走了默认匹配（true = 未精确匹配，走兜底） */
    private boolean isDefault;

    /** V2.1: 选中的 Skill 名称列表（由 SkillSelector 填充，Router 不再直接映射） */
    @Builder.Default
    private List<String> skillNames = List.of();
}
