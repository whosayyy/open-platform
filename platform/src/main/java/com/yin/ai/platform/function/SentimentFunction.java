package com.yin.ai.platform.function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayStructuredClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Gateway-backed sentiment analysis with bounded labels and score. */
public class SentimentFunction extends AbstractFunction {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "label", Map.of("type", "string", "enum", List.of("positive", "neutral", "negative")),
                    "score", Map.of("type", "number", "minimum", 0, "maximum", 1),
                    "reason", Map.of("type", "string")),
            "required", List.of("label", "score"));

    private final GatewayStructuredClient client;
    private final ObjectMapper objectMapper;

    public SentimentFunction(GatewayStructuredClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("analyzeSentiment")
                .description("Classify text sentiment as positive, neutral, or negative")
                .category("language")
                .risk(FunctionDefinition.Risk.READ_ONLY)
                .timeoutMs(2_000)
                .tags(Set.of("language", "classification", "structured"))
                .parameters(List.of(FunctionDefinition.ParamDef.builder().name("text").required(true)
                        .description("Text to analyze").build()))
                .returnType(FunctionDefinition.ReturnType.OBJECT)
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        String source = String.valueOf(input.getParameters().get("text"));
        return client.generate("sentiment_result", "Return a bounded sentiment assessment", SCHEMA,
                        source, "Analyze sentiment. Return only schema-compliant JSON.", 0.0,
                        input.getTenantId(), input.getUserId(), input.getRoles(), input.getPermissions())
                .map(this::parse)
                .orElseGet(() -> FunctionResult.fail("SENTIMENT_UNAVAILABLE", "Sentiment model unavailable"));
    }

    private FunctionResult parse(String json) {
        try {
            Map<String, Object> value = objectMapper.readValue(json, new TypeReference<>() {});
            String label = String.valueOf(value.get("label"));
            if (!Set.of("positive", "neutral", "negative").contains(label)
                    || !(value.get("score") instanceof Number number)
                    || number.doubleValue() < 0 || number.doubleValue() > 1) {
                return FunctionResult.fail("INVALID_SENTIMENT_RESULT", "Invalid label or score");
            }
            return FunctionResult.ok(value);
        } catch (Exception invalid) {
            return FunctionResult.fail("INVALID_SENTIMENT_RESULT", invalid.getMessage());
        }
    }
}
