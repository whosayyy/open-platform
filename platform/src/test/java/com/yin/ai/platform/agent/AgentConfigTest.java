package com.yin.ai.platform.agent;

import com.yin.ai.platform.agent.planner.PlanningMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link AgentConfig} 的工厂方法与结构化输出契约字段。
 */
@DisplayName("AgentConfig")
class AgentConfigTest {

    private static final StructuredOutputContract CONTRACT =
            new StructuredOutputContract("submitResult", Map.of("type", "object"));

    @Test
    @DisplayName("of(maxSteps, timeout, contract) → 契约模式，jsonResponse=false")
    void contractFactory() {
        AgentConfig config = AgentConfig.of(12, Duration.ofMinutes(3), CONTRACT);

        assertThat(config.maxSteps()).isEqualTo(12);
        assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(3));
        assertThat(config.outputContract()).isEqualTo(CONTRACT);
        assertThat(config.jsonResponse()).isFalse();
        assertThat(config.planningMode()).isEqualTo(PlanningMode.TEMPLATE);
        assertThat(config.maxRetriesPerStep()).isEqualTo(3);
        assertThat(config.enableHitl()).isFalse();
        assertThat(config.maxSubAgentDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("of(maxSteps, timeout, jsonResponse) → 契约字段为 null")
    void jsonResponseFactory() {
        AgentConfig config = AgentConfig.of(10, Duration.ofMinutes(2), true);

        assertThat(config.jsonResponse()).isTrue();
        assertThat(config.outputContract()).isNull();
    }

    @Test
    @DisplayName("defaults() → 无契约，jsonResponse=false")
    void defaults() {
        AgentConfig config = AgentConfig.defaults();

        assertThat(config.outputContract()).isNull();
        assertThat(config.jsonResponse()).isFalse();
        assertThat(config.maxSteps()).isEqualTo(12);
    }

    @Test
    @DisplayName("7 参构造兼容：契约字段为 null")
    void legacy7ArgConstructor() {
        AgentConfig config = new AgentConfig(5, Duration.ofMinutes(1), 2, true, 4,
                PlanningMode.LLM, true);

        assertThat(config.outputContract()).isNull();
        assertThat(config.jsonResponse()).isTrue();
        assertThat(config.planningMode()).isEqualTo(PlanningMode.LLM);
        assertThat(config.maxRetriesPerStep()).isEqualTo(2);
        assertThat(config.enableHitl()).isTrue();
        assertThat(config.maxSubAgentDepth()).isEqualTo(4);
    }
}
