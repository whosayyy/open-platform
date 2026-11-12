package com.yin.ai.platform.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayStructuredClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredLanguageFunctionTest {

    @Test
    void translationTraversesStructuredGatewayAndParsesContract() {
        GatewayStructuredClient client = mock(GatewayStructuredClient.class);
        when(client.generate(anyString(), anyString(), anyMap(), anyString(), anyString(), anyDouble(),
                eq("tenant-a"), eq("7"), any(), any()))
                .thenReturn(Optional.of("{\"translatedText\":\"hello\",\"targetLanguage\":\"English\"}"));
        TranslateFunction function = new TranslateFunction(client, new ObjectMapper());

        FunctionResult result = function.execute(FunctionInput.builder()
                .parameters(Map.of("text", "你好", "targetLanguage", "English"))
                .tenantId("tenant-a").userId("7").roles(Set.of()).permissions(Set.of()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(((Map<?, ?>) result.getData()).get("translatedText")).isEqualTo("hello");
    }

    @Test
    void invalidSentimentScoreIsRejected() {
        GatewayStructuredClient client = mock(GatewayStructuredClient.class);
        when(client.generate(anyString(), anyString(), anyMap(), anyString(), anyString(), anyDouble(),
                any(), any(), any(), any()))
                .thenReturn(Optional.of("{\"label\":\"positive\",\"score\":1.5}"));
        SentimentFunction function = new SentimentFunction(client, new ObjectMapper());

        FunctionResult result = function.execute(FunctionInput.builder()
                .parameters(Map.of("text", "great")).tenantId("tenant-a").userId("7").build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("INVALID_SENTIMENT_RESULT");
    }
}
