package com.yin.ai.platform.adapter.router.observability;

import com.yin.ai.platform.core.router.RouterTelemetry;
import com.yin.ai.platform.observe.AiMetrics;

/** Bridges core router telemetry to the platform's Micrometer-backed metrics facade. */
public final class AiMetricsRouterTelemetry implements RouterTelemetry {

    private final AiMetrics metrics;

    public AiMetricsRouterTelemetry(AiMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void recordCacheHit() {
        metrics.recordCacheHit();
    }

    @Override
    public void recordCacheMiss() {
        metrics.recordCacheMiss();
    }

    @Override
    public void recordMatcherHit(String matcherName) {
        metrics.recordMatcherHit(matcherName);
    }

    @Override
    public void recordMatcherDuration(String matcherName, long durationMicros) {
        metrics.recordMatcherDuration(matcherName, durationMicros);
    }

    @Override
    public void recordIntentDistribution(String intent) {
        metrics.recordIntentDistribution(intent);
    }

    @Override
    public void recordRouterMatch(long durationMicros, boolean defaultMatch) {
        metrics.recordRouterMatch(durationMicros, defaultMatch);
    }
}
