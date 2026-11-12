package com.yin.ai.platform.observe.slo;

/**
 * SLO 指标类型。
 */
public enum SloMetric {
    /** 可用性（成功率） */
    AVAILABILITY,
    /** P99 延迟（毫秒） */
    LATENCY_P99,
    /** P95 延迟（毫秒） */
    LATENCY_P95,
    /** 错误率 */
    ERROR_RATE,
    /** 首 Token 延迟 P95 */
    TTFT_P95,
    /** 意图路由准确率 */
    ACCURACY,
    /** 召回率（RAG） */
    RECALL
}
