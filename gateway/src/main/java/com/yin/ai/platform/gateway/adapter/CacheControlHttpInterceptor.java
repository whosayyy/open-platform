package com.yin.ai.platform.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * HTTP 请求拦截器，将系统消息的 content 从纯文本转换为带 cache_control 标记的 content block 数组。
 * <p>
 * 仅当 {@link CacheControlContext} 标记为 true 时才执行转换。
 * 转换前：{"role":"system","content":"You are..."}
 * 转换后：{"role":"system","content":[{"type":"text","text":"You are...","cache_control":{"type":"ephemeral"}}]}
 */
@Slf4j
public class CacheControlHttpInterceptor implements ClientHttpRequestInterceptor {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        if (!CacheControlContext.isEnabled()) {
            return execution.execute(request, body);
        }

        try {
            byte[] modifiedBody = injectCacheControl(body);
            CacheControlContext.clear();
            return execution.execute(request, modifiedBody);
        } catch (Exception e) {
            log.warn("[CACHE] Failed to inject cache_control, sending original request", e);
            CacheControlContext.clear();
            return execution.execute(request, body);
        }
    }

    byte[] injectCacheControl(byte[] body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode messages = root.get("messages");

        if (messages == null || !messages.isArray()) {
            return body;
        }

        boolean modified = false;
        for (JsonNode msg : messages) {
            if (!"system".equals(msg.path("role").asText())) {
                continue;
            }

            JsonNode contentNode = msg.get("content");
            if (contentNode == null || !contentNode.isTextual()) {
                continue;
            }

            String text = contentNode.asText();
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", text);

            ObjectNode cacheControl = objectMapper.createObjectNode();
            cacheControl.put("type", "ephemeral");
            textBlock.set("cache_control", cacheControl);

            ArrayNode contentArray = objectMapper.createArrayNode();
            contentArray.add(textBlock);

            ((ObjectNode) msg).set("content", contentArray);
            modified = true;
            log.debug("[CACHE] injected cache_control into system message");
        }

        return modified ? objectMapper.writeValueAsBytes(root) : body;
    }
}
