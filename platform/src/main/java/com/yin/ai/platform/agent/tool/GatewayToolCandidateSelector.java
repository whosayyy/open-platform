package com.yin.ai.platform.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.gateway.GatewayStructuredClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Structured Gateway implementation used only after deterministic authorization and threshold checks. */
@Component
public class GatewayToolCandidateSelector implements ToolCandidateSelector {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("tools", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", java.util.List.of("tools"));

    private final GatewayStructuredClient gateway;
    private final ObjectMapper mapper;

    public GatewayToolCandidateSelector(GatewayStructuredClient gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @Override
    public Set<String> select(String goal, Collection<FunctionDefinition> candidates, AgentContext context) {
        String catalog = candidates.stream()
                .map(def -> def.getName() + ": " + String.valueOf(def.getDescription()))
                .collect(java.util.stream.Collectors.joining("\n"));
        String tenantId = string(context, "tenantId");
        String userId = string(context, "userId");
        String json = gateway.generate("select_agent_tools", "Select the smallest sufficient tool set",
                        SCHEMA, "Goal:\n" + goal + "\nAuthorized tools:\n" + catalog,
                        "Return only tools from the authorized catalog.", 0.0,
                        tenantId, userId, stringSet(context, "roles"), stringSet(context, "permissions"))
                .orElseThrow(() -> new IllegalStateException("tool selector unavailable"));
        try {
            JsonNode tools = mapper.readTree(json).path("tools");
            Set<String> selected = new LinkedHashSet<>();
            tools.forEach(node -> selected.add(node.asText()));
            return selected;
        } catch (Exception invalid) {
            throw new IllegalStateException("invalid tool selector response", invalid);
        }
    }

    private String string(AgentContext context, String key) {
        Object value = context != null ? context.getVariable(key) : null;
        return value == null ? null : value.toString();
    }

    private Set<String> stringSet(AgentContext context, String key) {
        Object value = context != null ? context.getVariable(key) : null;
        if (!(value instanceof Collection<?> values)) return Set.of();
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
