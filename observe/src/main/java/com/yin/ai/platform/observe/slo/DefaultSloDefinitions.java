package com.yin.ai.platform.observe.slo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 平台默认 SLO 定义。
 */
@Configuration
public class DefaultSloDefinitions {

    @Bean
    public List<SloDefinition> platformSlos() {
        return List.of(
                new SloDefinition("ai-gateway-availability",
                        SloMetric.AVAILABILITY, 0.999, Duration.ofMinutes(30), "P1"),
                new SloDefinition("ai-chat-ttft-p95",
                        SloMetric.TTFT_P95, 5000, Duration.ofMinutes(30), "P2"),
                new SloDefinition("ai-router-accuracy",
                        SloMetric.ACCURACY, 0.95, Duration.ofHours(1), "P2"),
                new SloDefinition("ai-rag-recall-p99",
                        SloMetric.LATENCY_P99, 500, Duration.ofMinutes(30), "P3"),
                new SloDefinition("ai-function-error-rate",
                        SloMetric.ERROR_RATE, 0.01, Duration.ofMinutes(30), "P1")
        );
    }
}
