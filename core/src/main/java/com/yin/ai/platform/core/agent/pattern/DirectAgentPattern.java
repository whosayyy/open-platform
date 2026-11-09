package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.model.ModelResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/** Single-model-call policy used for simple requests and compatibility. */
public final class DirectAgentPattern implements AgentPattern {

    @Override
    public AgentMode mode() {
        return AgentMode.DIRECT;
    }

    @Override
    public PatternResult execute(PatternContext context) {
        context.emit("model.started", Map.of("allowedToolCount",
                context.availableTools().size()));
        ModelResponse response = context.callModel(context.initialMessages(),
                context.availableTools(), Map.of("agentMode", mode().name()));
        Map<String, Object> metadata = new LinkedHashMap<>(response.metadata());
        metadata.put("mode", mode().name());
        metadata.put("finishReason", response.finishReason() == null
                ? "" : response.finishReason());
        metadata.put("usage", response.usage());
        return new PatternResult(response.content(), metadata);
    }
}
