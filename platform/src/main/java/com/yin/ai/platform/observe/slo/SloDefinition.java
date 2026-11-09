package com.yin.ai.platform.observe.slo;

import java.time.Duration;

/**
 * SLO 定义。
 *
 * @param name              唯一标识，如 "ai-chat-availability"
 * @param metric            指标类型
 * @param target            目标值（如 0.999 表示 99.9%）
 * @param evaluationWindow  评估窗口（滚动）
 * @param alertSeverity     告警严重级别
 */
public record SloDefinition(
        String name,
        SloMetric metric,
        double target,
        Duration evaluationWindow,
        String alertSeverity) {
}
