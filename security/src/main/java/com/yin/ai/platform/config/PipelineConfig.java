package com.yin.ai.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.pipeline")
public class PipelineConfig {
    private int sinkBufferSize = 256;
    private int maxConcurrency = 5000;
    private long globalTimeoutMs = 300_000;
    private long firstTokenTimeoutMs = 30_000;
    private long toolExecutionTimeoutMs = 10_000;
    private int maxToolCallTurns = 5;
    private double temperature = 0.7;
    private int maxTokens = 4096;
}
