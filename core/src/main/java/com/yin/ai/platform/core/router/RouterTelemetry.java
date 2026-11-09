package com.yin.ai.platform.core.router;

/** Minimal observability SPI for routing decisions. */
public interface RouterTelemetry {

    RouterTelemetry NOOP = new RouterTelemetry() {
    };

    default void recordCacheHit() {
    }

    default void recordCacheMiss() {
    }

    default void recordMatcherHit(String matcherName) {
    }

    default void recordMatcherDuration(String matcherName, long durationMicros) {
    }

    default void recordIntentDistribution(String intent) {
    }

    default void recordRouterMatch(long durationMicros, boolean defaultMatch) {
    }
}
