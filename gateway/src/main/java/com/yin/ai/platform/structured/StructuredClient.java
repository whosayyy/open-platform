package com.yin.ai.platform.structured;

import com.yin.ai.platform.gateway.GatewayStructuredClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Compatibility facade for callers that used the original structured client.
 * All calls now traverse {@link GatewayStructuredClient}.
 */
@Component
public class StructuredClient {

    private final GatewayStructuredClient gatewayStructuredClient;

    public StructuredClient(GatewayStructuredClient gatewayStructuredClient) {
        this.gatewayStructuredClient = gatewayStructuredClient;
    }

    public Optional<String> generate(String toolName,
                                     String toolDescription,
                                     Map<String, Object> schema,
                                     String userPrompt,
                                     String systemPrompt,
                                     Double temperature) {
        return gatewayStructuredClient.generate(toolName, toolDescription, schema,
                userPrompt, systemPrompt, temperature);
    }

    public boolean isAvailable() {
        return gatewayStructuredClient.isAvailable();
    }
}
