package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.agent.AgentRequest;

import java.util.Map;

/** Explicit request hints first, with direct execution as the compatibility default. */
public final class DefaultAgentPatternRouter implements AgentPatternRouter {

    @Override
    public AgentMode route(AgentRequest request) {
        Map<String, String> metadata = request.metadata();
        String forced = firstText(metadata.get("forcedMode"), metadata.get("agentMode"),
                metadata.get("mode"));
        if (forced != null) return AgentMode.parse(forced);
        if (Boolean.parseBoolean(metadata.getOrDefault("deepMode", "false"))) {
            return AgentMode.DEEP_RESEARCH;
        }
        return AgentMode.DIRECT;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
