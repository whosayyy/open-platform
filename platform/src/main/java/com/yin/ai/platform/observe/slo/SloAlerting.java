package com.yin.ai.platform.observe.slo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * SLO 告警 — 基于错误预算燃烧率的多级告警。
 * <p>
 * 告警规则：
 * <ul>
 *   <li>5min 内消耗 10% 错误预算 → P1 告警</li>
 *   <li>1h 内消耗 50% 错误预算 → P2 告警</li>
 *   <li>剩余预算 < 5% → P1 告警</li>
 * </ul>
 * <p>
 * 当前实现：日志输出。生产环境集成 Prometheus AlertManager / 企微 / 邮件。
 */
public class SloAlerting {

    private static final Logger log = LoggerFactory.getLogger(SloAlerting.class);

    /**
     * 评估所有 SLO 状态并触发相应级别的告警。
     */
    public void evaluate(Map<String, SloStatus> sloStatuses) {
        if (sloStatuses == null || sloStatuses.isEmpty()) {
            return;
        }

        for (var entry : sloStatuses.entrySet()) {
            SloStatus status = entry.getValue();
            String sloName = entry.getKey();

            if (!status.compliant()) {
                if (status.isCriticalBurn()) {
                    log.error("[SLO-ALERT] P1: {} — critical budget burn ({}% used, {}% remaining, target={})",
                            sloName, pct(status.errorBudgetUsed()), pct(status.remainingBudget()), status.target());
                } else if (status.isBurningBudget()) {
                    log.warn("[SLO-ALERT] P2: {} — budget burning ({}% used, {}% remaining, target={})",
                            sloName, pct(status.errorBudgetUsed()), pct(status.remainingBudget()), status.target());
                } else {
                    log.info("[SLO-ALERT] {} — non-compliant (value={}, target={}, budget used={}%)",
                            sloName, status.currentValue(), status.target(), pct(status.errorBudgetUsed()));
                }
            }
        }
    }

    private static String pct(double value) {
        return String.format("%.1f", value * 100);
    }
}
