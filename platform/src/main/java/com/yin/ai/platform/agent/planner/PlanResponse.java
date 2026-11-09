package com.yin.ai.platform.agent.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LLM 规划响应 DTO — Jackson 直接反序列化，避免手撸 Map 的脆弱性。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PlanResponse(
        String direct,
        List<StepDef> steps) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StepDef(
            String id,
            String description,
            String tool,
            @JsonProperty("toolParams")
            Map<String, Object> toolParams,
            @JsonProperty("dependsOn")
            List<String> dependsOn,
            String reason) {

        Step toStep() {
            return new Step(
                    id,
                    description,
                    tool,
                    toolParams != null ? Collections.unmodifiableMap(toolParams) : Collections.emptyMap(),
                    dependsOn != null ? Collections.unmodifiableList(dependsOn) : Collections.emptyList(),
                    reason);
        }
    }

    boolean hasSteps() {
        return steps != null && !steps.isEmpty();
    }

    boolean hasDirect() {
        return direct != null && !direct.isBlank();
    }

    boolean isEmpty() {
        return !hasDirect() && !hasSteps();
    }
}
