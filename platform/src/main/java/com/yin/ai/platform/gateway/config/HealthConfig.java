package com.yin.ai.platform.gateway.config;

import lombok.Data;

@Data
public class HealthConfig {
    /** 连续失败多少次触发熔断（默认 5） */
    private int failureThreshold = 5;
    /** 熔断后等待多久进入半开状态（秒，默认 30） */
    private long recoveryWaitSeconds = 30;
    /** 健康检查间隔（秒，默认 15） */
    private long healthCheckIntervalSeconds = 15;
}
