package com.yin.ai.platform.function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 通用文本向量化 Function — 调用 OpenAI 兼容 Embedding API。
 * <p>
 * callableByLLM=false：仅供平台内部使用（如 EmbeddingMatcher），不对 LLM 暴露。
 * 支持任何 OpenAI 兼容的 Embedding 服务（DashScope / DeepSeek / OpenAI / 本地模型）。
 * <p>
 * 配置项：
 * <ul>
 *   <li>{@code ai.embedding.api-key} — API Key</li>
 *   <li>{@code ai.embedding.api-url} — API 地址（默认 http://localhost:8000/v1/embeddings）</li>
 *   <li>{@code ai.embedding.model} — 模型名（默认 text-embedding-v3）</li>
 * </ul>
 */
@Slf4j
public class EmbeddingFunction extends AbstractFunction {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Supplier<String> apiKeyProvider;
    private final Supplier<String> apiUrlProvider;
    private final Supplier<String> modelProvider;

    public EmbeddingFunction(
            @Value("${ai.embedding.api-key:}") String apiKey,
            @Value("${ai.embedding.api-url:http://localhost:8000/v1/embeddings}") String apiUrl,
            @Value("${ai.embedding.model:text-embedding-v3}") String model) {
        this.apiKeyProvider = () -> apiKey;
        this.apiUrlProvider = () -> apiUrl;
        this.modelProvider = () -> model;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("embedding")
                .description("将文本转换为向量表示（embedding），用于语义搜索和相似度匹配")
                .version("1.0.0")
                .category("system")
                .risk(FunctionDefinition.Risk.READ_ONLY)
                .timeoutMs(15000)
                .callableByLLM(false)
                .tags(Set.of("embedding", "vector", "system"))
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder()
                                .name("text")
                                .type(FunctionDefinition.ParamType.STRING)
                                .required(true)
                                .description("需要向量化的文本内容")
                                .build()
                ))
                .returnType(FunctionDefinition.ReturnType.OBJECT)
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        String text = (String) input.getParameters().get("text");
        String apiKey = apiKeyProvider.get();

        if (apiKey == null || apiKey.isEmpty()) {
            return FunctionResult.fail("EMBEDDING_NO_API_KEY", "Embedding API key 未配置");
        }

        try {
            var restTemplate = new org.springframework.web.client.RestTemplate();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("input", text);
            requestBody.put("model", modelProvider.get());
            requestBody.put("encoding_format", "float");

            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            var request = new org.springframework.http.HttpEntity<>(requestBody, headers);

            var response = restTemplate.postForEntity(apiUrlProvider.get(), request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return FunctionResult.fail("EMBEDDING_API_ERROR",
                        "Embedding API returned " + response.getStatusCode());
            }

            float[] embedding = parseEmbedding(response.getBody());
            if (embedding == null || embedding.length == 0) {
                return FunctionResult.fail("EMBEDDING_PARSE_ERROR", "Embedding 响应解析失败");
            }

            return FunctionResult.ok(embedding);

        } catch (Exception e) {
            log.error("Embedding failed, text length={}", text != null ? text.length() : 0, e);
            return FunctionResult.fail("EMBEDDING_ERROR", e.getMessage());
        }
    }

    private float[] parseEmbedding(String jsonBody) throws Exception {
        JsonNode root = objectMapper.readTree(jsonBody);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        JsonNode embedding = data.get(0).get("embedding");
        if (embedding == null || !embedding.isArray()) {
            return null;
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }
}
