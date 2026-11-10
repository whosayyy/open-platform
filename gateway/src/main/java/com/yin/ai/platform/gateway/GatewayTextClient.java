package com.yin.ai.platform.gateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import reactor.core.publisher.Flux;
import com.yin.ai.platform.engine.stream.StreamPacket;

/**
 * Small high-level facade for plain text generation through the AI Gateway.
 */
@Component
public class GatewayTextClient {

    private final ObjectProvider<UnifiedChatClient> gatewayProvider;

    public GatewayTextClient(ObjectProvider<UnifiedChatClient> gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    public GatewayResponse generate(String caller,
                                    String userPrompt,
                                    String systemPrompt,
                                    Double temperature,
                                    ModelExecutionOptions executionOptions) {
        return generate(caller, userPrompt, systemPrompt, temperature, executionOptions, Set.of());
    }

    public GatewayResponse generate(String caller,
                                    String userPrompt,
                                    String systemPrompt,
                                    Double temperature,
                                    ModelExecutionOptions executionOptions,
                                    Set<String> functionNames) {
        UnifiedChatClient gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("AI Gateway is unavailable");
        }
        GatewayOptions options = new GatewayOptions();
        options.setTemperature(temperature);
        options.setModelExecutionOptions(executionOptions);
        GatewayRequest request = new GatewayRequest(List.of(GatewayMessage.user(userPrompt)), options);
        request.setSystemPrompt(systemPrompt);
        request.setCaller(caller);
        request.setFunctionNames(functionNames == null ? Set.of() : Set.copyOf(functionNames));
        return gateway.chat(request);
    }

    public GatewayResponse generate(String caller, String userPrompt, String systemPrompt,
                                    Double temperature) {
        return generate(caller, userPrompt, systemPrompt, temperature, null);
    }

    public Flux<StreamPacket> stream(String caller,
                                     String userPrompt,
                                     String systemPrompt,
                                     Double temperature,
                                     ModelExecutionOptions executionOptions) {
        return stream(caller, userPrompt, systemPrompt, temperature, executionOptions, Set.of());
    }

    public Flux<StreamPacket> stream(String caller,
                                     String userPrompt,
                                     String systemPrompt,
                                     Double temperature,
                                     ModelExecutionOptions executionOptions,
                                     Set<String> functionNames) {
        UnifiedChatClient gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return Flux.error(new IllegalStateException("AI Gateway is unavailable"));
        }
        GatewayOptions options = new GatewayOptions();
        options.setTemperature(temperature);
        options.setModelExecutionOptions(executionOptions);
        GatewayRequest request = new GatewayRequest(List.of(GatewayMessage.user(userPrompt)), options);
        request.setSystemPrompt(systemPrompt);
        request.setCaller(caller);
        request.setFunctionNames(functionNames == null ? Set.of() : Set.copyOf(functionNames));
        return gateway.chatStream(request);
    }

    public boolean isAvailable() {
        return gatewayProvider.getIfAvailable() != null;
    }
}
