package com.yin.ai.platform.observe.config;

import com.yin.ai.platform.observe.AiMetrics;
import com.yin.ai.platform.observe.slo.SloAlerting;
import com.yin.ai.platform.observe.slo.SloCalculator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability 模块 Spring 配置。
 */
@Configuration
public class ObservabilityConfiguration {

    @Bean
    public AiMetrics aiMetrics(MeterRegistry meterRegistry) {
        return new AiMetrics(meterRegistry);
    }

    @Bean
    public SloCalculator sloCalculator(MeterRegistry meterRegistry) {
        return new SloCalculator(meterRegistry);
    }

    @Bean
    public SloAlerting sloAlerting() {
        return new SloAlerting();
    }
}
