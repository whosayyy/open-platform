package com.yin.ai.platform.observe;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RouterFirstMonitorTest {

    @Test
    void alertsOnlyAfterMinimumTrafficAndAgentRatioExceedsTwentyPercent() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RouterFirstMonitor monitor = new RouterFirstMonitor(meters,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC));

        for (int i = 0; i < 21; i++) monitor.recordAgent();
        for (int i = 0; i < 79; i++) monitor.recordSkill();

        assertThat(monitor.snapshot().total()).isEqualTo(100);
        assertThat(monitor.snapshot().agentRatio()).isEqualTo(0.21);
        assertThat(monitor.snapshot().alerting()).isTrue();
        assertThat(meters.counter("ai.router.path.alerts", "reason", "agent_ratio_high").count())
                .isEqualTo(1.0);
    }
}
