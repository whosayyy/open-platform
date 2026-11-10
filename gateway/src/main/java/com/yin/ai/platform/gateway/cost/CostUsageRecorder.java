package com.yin.ai.platform.gateway.cost;

/** Service-side persistence port; platform remains independent from MyBatis. */
@FunctionalInterface
public interface CostUsageRecorder {

    void record(CostUsageEvent event);
}
