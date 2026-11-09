package com.yin.ai.platform.rag.enhanced;

import com.yin.ai.platform.core.rag.query.QueryExpansionStrategy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayStructuredClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Optional, timeout-bounded LLM query expansion through the unified Gateway. */
@Slf4j
@Component
public class GatewayQueryExpansionStrategy implements QueryExpansionStrategy {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("queries", Map.of(
                    "type", "array", "items", Map.of("type", "string"), "maxItems", 2)),
            "required", List.of("queries"));

    private final GatewayStructuredClient client;
    private final ObjectMapper objectMapper;
    private final EnhancedRagProperties properties;

    public GatewayQueryExpansionStrategy(GatewayStructuredClient client,
                                         ObjectMapper objectMapper,
                                         EnhancedRagProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<String> expand(String query, Long userId) {
        if (!properties.getMultiQuery().isLlmExpansionEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return CompletableFuture.supplyAsync(() -> client.generate(
                            "rag_query_expansion", "Return at most two alternative retrieval queries", SCHEMA,
                            query, "Expand the search query with closely related terms. Return JSON only.", 0.1,
                            org.slf4j.MDC.get("tenantId"), userId != null ? userId.toString() : null,
                            java.util.Set.of(), java.util.Set.of()).map(this::parse).orElse(List.of()))
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(failure -> List.of())
                    .join();
        } catch (RuntimeException failure) {
            log.debug("[QUERY-EXPANSION] gateway fallback: {}", failure.getMessage());
            return List.of();
        }
    }

    @Override
    public int priority() {
        return 200;
    }

    private List<String> parse(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            Object raw = parsed.get("queries");
            if (!(raw instanceof List<?> values)) return List.of();
            return values.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(String::trim).filter(value -> !value.isBlank()).distinct().limit(2).toList();
        } catch (Exception invalid) {
            return List.of();
        }
    }
}
