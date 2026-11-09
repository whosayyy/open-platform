package com.yin.ai.platform.memory.evaluation;

import java.util.List;

/**
 * 评测指标接口 — 计算检索质量。
 */
public interface EvaluationMetric {

    /** 指标名称 */
    String name();

    /** 计算指标得分 */
    double compute(List<RetrievedDocument> retrieved, List<String> relevantDocIds, int k);

    /** 指标方向：true = 越高越好 */
    default boolean higherIsBetter() {
        return true;
    }
}
