package com.yin.ai.platform.core.router;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 意图匹配结果，由 IntentMatcher.match() 返回。
 */
@Data
@Builder
public class IntentMatchResult {

    /** 匹配到的意图（由调用方定义的 String 标识符）。 */
    private String intent;

    /** 置信度 [0.0, 1.0] */
    private double confidence;

    /** 匹配器名称（用于调试） */
    private String matchedBy;

    /** 命中的规则描述（用于调试） */
    private String matchedRule;

    /** 扩展元数据 */
    @Builder.Default
    private Map<String, Object> metadata = Map.of();
}
