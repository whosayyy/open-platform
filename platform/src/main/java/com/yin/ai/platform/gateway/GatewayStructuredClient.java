package com.yin.ai.platform.gateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Structured-output facade that still traverses the complete AI Gateway chain.
 */
@Component
public class GatewayStructuredClient {

    private final ObjectProvider<UnifiedChatClient> gatewayProvider;

    public GatewayStructuredClient(ObjectProvider<UnifiedChatClient> gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    public Optional<String> generate(String toolName,
                                     String toolDescription,
                                     Map<String, Object> schema,
                                     String userPrompt,
                                     String systemPrompt,
                                     Double temperature) {
        return generate(toolName, toolDescription, schema, userPrompt, systemPrompt, temperature,
                null, null, Set.of(), Set.of());
    }

    public Optional<String> generate(String toolName,
                                     String toolDescription,
                                     Map<String, Object> schema,
                                     String userPrompt,
                                     String systemPrompt,
                                     Double temperature,
                                     String tenantId,
                                     String userId,
                                     Set<String> roles,
                                     Set<String> permissions) {
        if (toolName == null || toolName.isBlank() || schema == null || userPrompt == null) {
            return Optional.empty();
        }
        UnifiedChatClient gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return Optional.empty();
        }

        GatewayOptions options = new GatewayOptions();
        options.setTemperature(temperature != null ? temperature : 0.2);
        options.setEnableFunctionCalling(true);
        options.setEnableToolExecution(false);
        options.setForcedToolName(toolName);
        options.setOutputSchema(schema);
        options.setForceToolChoice(true);
        options.setJsonResponse(true);

        GatewayRequest request = new GatewayRequest(List.of(GatewayMessage.user(userPrompt)), options);
        request.setSystemPrompt(systemPrompt);
        request.setCaller("structured:" + toolName);
        request.setFunctionNames(Set.of(toolName));
        request.setTenantId(tenantId);
        request.setUserId(userId);
        request.setRoles(roles != null ? Set.copyOf(roles) : Set.of());
        request.setPermissions(permissions != null ? Set.copyOf(permissions) : Set.of());
        if (toolDescription != null && !toolDescription.isBlank()) {
            request.setPromptVariables(Map.of("toolDescription", toolDescription));
        }

        try {
            GatewayResponse response = gateway.chat(request);
            if (response == null) {
                return Optional.empty();
            }
            if (response.getToolCallArguments() != null
                    && !response.getToolCallArguments().isBlank()) {
                return Optional.of(response.getToolCallArguments());
            }
            if (response.getToolCalls() != null) {
                Optional<String> matching = response.getToolCalls().stream()
                        .filter(call -> toolName.equals(call.getName()))
                        .map(GatewayResponse.ToolCall::getArguments)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst();
                if (matching.isPresent()) {
                    return matching;
                }
            }
            String content = response.getContent();
            return content == null || content.isBlank() ? Optional.empty() : Optional.of(content);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    public boolean isAvailable() {
        return gatewayProvider.getIfAvailable() != null;
    }
}
