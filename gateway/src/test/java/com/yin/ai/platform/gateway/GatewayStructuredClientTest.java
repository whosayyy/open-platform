package com.yin.ai.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayStructuredClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void structuredOutputTraversesGatewayWithForcedToolContract() {
        UnifiedChatClient gateway = mock(UnifiedChatClient.class);
        ObjectProvider<UnifiedChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        GatewayResponse response = new GatewayResponse();
        response.setToolCallArguments("{\"value\":\"ok\"}");
        when(gateway.chat(any(GatewayRequest.class))).thenReturn(response);

        Optional<String> result = new GatewayStructuredClient(provider).generate(
                "submit", "submit result", Map.of("type", "object"),
                "prompt", "system", 0.1);

        assertThat(result).contains("{\"value\":\"ok\"}");
        var captor = org.mockito.ArgumentCaptor.forClass(GatewayRequest.class);
        verify(gateway).chat(captor.capture());
        assertThat(captor.getValue().getOptions().getForcedToolName()).isEqualTo("submit");
        assertThat(captor.getValue().getFunctionNames()).containsExactly("submit");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableGatewayReturnsEmpty() {
        ObjectProvider<UnifiedChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        assertThat(new GatewayStructuredClient(provider).generate(
                "submit", "description", Map.of("type", "object"), "prompt", null, null))
                .isEmpty();
    }
}
