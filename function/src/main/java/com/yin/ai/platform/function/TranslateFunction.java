package com.yin.ai.platform.function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayStructuredClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Gateway-backed translation with a strict JSON contract. */
public class TranslateFunction extends AbstractFunction {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "translatedText", Map.of("type", "string"),
                    "detectedSourceLanguage", Map.of("type", "string"),
                    "targetLanguage", Map.of("type", "string")),
            "required", List.of("translatedText", "targetLanguage"));

    private final GatewayStructuredClient client;
    private final ObjectMapper objectMapper;

    public TranslateFunction(GatewayStructuredClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("translateText")
                .description("Translate text to a requested language and return structured JSON")
                .category("language")
                .risk(FunctionDefinition.Risk.READ_ONLY)
                .timeoutMs(2_000)
                .tags(Set.of("language", "structured"))
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder().name("text").required(true)
                                .description("Text to translate").build(),
                        FunctionDefinition.ParamDef.builder().name("targetLanguage").required(true)
                                .description("Target language or locale").build()))
                .returnType(FunctionDefinition.ReturnType.OBJECT)
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        String text = String.valueOf(input.getParameters().get("text"));
        String target = String.valueOf(input.getParameters().get("targetLanguage"));
        return client.generate("translation_result", "Return only the translation result", SCHEMA,
                        "Translate the following text to " + target + ":\n" + text,
                        "You are a precise translator. Preserve meaning and do not add commentary.", 0.1,
                        input.getTenantId(), input.getUserId(), input.getRoles(), input.getPermissions())
                .map(this::parse)
                .orElseGet(() -> FunctionResult.fail("TRANSLATION_UNAVAILABLE", "Translation model unavailable"));
    }

    private FunctionResult parse(String json) {
        try {
            Map<String, Object> value = objectMapper.readValue(json, new TypeReference<>() {});
            if (!(value.get("translatedText") instanceof String translated) || translated.isBlank()) {
                return FunctionResult.fail("INVALID_TRANSLATION_RESULT", "translatedText is required");
            }
            return FunctionResult.ok(value);
        } catch (Exception invalid) {
            return FunctionResult.fail("INVALID_TRANSLATION_RESULT", invalid.getMessage());
        }
    }
}
