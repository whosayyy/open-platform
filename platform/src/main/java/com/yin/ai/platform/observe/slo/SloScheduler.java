package com.yin.ai.platform.observe.slo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 【企业级缺口修复】SLO 评估调度器 — 周期计算并触发告警。
 * <p>
 * <b>问题</b>：SloAlerting.evaluate() 实现完整但从无调用方（仅 Bean 注册），
 * 错误预算告警永不触发 —— 典型的"建了类没接线"。
 * <p>
 * <b>修复</b>：本调度器每 60s 计算默认 SLO 状态并交给 SloAlerting 评估，
 * 使 P1/P2 告警真正落地（当前实现输出日志，可扩展 AlertManager/企微/邮件）。
 */
@Slf4j
@Component
public class SloScheduler {

    private final SloCalculator calculator;
    private final SloAlerting alerting;
    private final DefaultSloDefinitions definitions;

    public SloScheduler(SloCalculator calculator,
                        SloAlerting alerting,
                        DefaultSloDefinitions definitions) {
        this.calculator = calculator;
        this.alerting = alerting;
        this.definitions = definitions;
    }

    /**
     * 每 60s 计算一次 SLO 状态并评估告警。
     * 静默成功：无违规时只打 DEBUG（避免日志噪音），违规时由 SloAlerting 输出 WARN/ERROR。
     */
    @Scheduled(fixedDelayString = "${ai.observability.slo.interval-ms:60000}",
            initialDelayString = "${ai.observability.slo.initial-delay-ms:30000}")
    public void evaluateSlo() {
        try {
            List<SloDefinition> sloList = definitions.platformSlos();
            if (sloList.isEmpty()) {
                return;
            }
            Map<String, SloStatus> statuses = calculator.calculateAll(sloList);
            if (statuses.isEmpty()) {
                return;
            }
            // 交给 SloAlerting 按错误预算燃烧率分级告警（当前日志输出）
            alerting.evaluate(statuses);
        } catch (Exception e) {
            log.warn("[SLO] evaluation failed: {}", e.getMessage());
        }
    }
}
