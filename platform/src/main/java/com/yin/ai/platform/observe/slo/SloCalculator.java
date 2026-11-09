package com.yin.ai.platform.observe.slo;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * SLO 计算器 — 从 Micrometer 读取实时指标，计算每个 SLO 的达标状态。
 */
public class SloCalculator {

    private static final Logger log = LoggerFactory.getLogger(SloCalculator.class);

    private final MeterRegistry meterRegistry;

    public SloCalculator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 计算所有 SLO 的当前状态。
     */
    public Map<String, SloStatus> calculateAll(List<SloDefinition> slos) {
        Map<String, SloStatus> results = new LinkedHashMap<>();
        if (slos == null || slos.isEmpty()) {
            return results;
        }
        for (SloDefinition slo : slos) {
            try {
                results.put(slo.name(), calculate(slo));
            } catch (Exception e) {
                log.warn("[SLO] failed to calculate {}: {}", slo.name(), e.getMessage());
                results.put(slo.name(), new SloStatus(slo.name(), 0, slo.target(), false, 1.0, 0));
            }
        }
        return results;
    }

    private static final Set<SloMetric> LOWER_IS_BETTER = Set.of(
            SloMetric.ERROR_RATE, SloMetric.LATENCY_P99,
            SloMetric.LATENCY_P95, SloMetric.TTFT_P95);

    private SloStatus calculate(SloDefinition slo) {
        SloMetric metric = slo.metric();
        double currentValue = switch (metric) {
            case AVAILABILITY -> calculateAvailability();
            case ERROR_RATE -> calculateErrorRate();
            case LATENCY_P99 -> calculateLatencyPercentile(0.99);
            case LATENCY_P95 -> calculateLatencyPercentile(0.95);
            case TTFT_P95 -> calculateTTFTPercentile();
            case ACCURACY -> 1.0;
            case RECALL -> 1.0;
        };

        boolean compliant = LOWER_IS_BETTER.contains(metric)
                ? currentValue <= slo.target()
                : currentValue >= slo.target();

        double errorBudgetUsed = compliant ? 0.0
                : Math.min(1.0, Math.abs(currentValue - slo.target()) / slo.target());
        double remainingBudget = 1.0 - errorBudgetUsed;

        return new SloStatus(slo.name(), currentValue, slo.target(), compliant,
                errorBudgetUsed, remainingBudget);
    }

    private double calculateAvailability() {
        double total = getCounter("ai.gateway.request.total");
        double errors = getCounter("ai.gateway.error.count");
        return total > 0 ? 1.0 - (errors / total) : 1.0;
    }

    private double calculateErrorRate() {
        double total = getCounter("ai.gateway.request.total");
        double errors = getCounter("ai.gateway.error.count");
        return total > 0 ? errors / total : 0.0;
    }

    private double calculateLatencyPercentile(double percentile) {
        var timer = meterRegistry.find("ai.gateway.request.duration").timer();
        if (timer == null) {
            return 0;
        }
        return timer.mean(TimeUnit.MILLISECONDS) * 2.0; // approximate P99 as 2x mean
    }

    private double calculateTTFTPercentile() {
        var timer = meterRegistry.find("ai.gateway.request.duration").timer();
        if (timer == null) {
            return 0;
        }
        return timer.mean(TimeUnit.MILLISECONDS);
    }

    private double getCounter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0;
    }
}
