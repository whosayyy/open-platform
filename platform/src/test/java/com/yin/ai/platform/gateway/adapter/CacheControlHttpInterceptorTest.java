package com.yin.ai.platform.gateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheControlHttpInterceptor — JSON body cache_control 注入")
class CacheControlHttpInterceptorTest {

    private CacheControlHttpInterceptor interceptor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        interceptor = new CacheControlHttpInterceptor();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("系统消息 content 从字符串转换为带 cache_control 的数组")
    void shouldInjectCacheControlIntoSystemMessage() throws Exception {
        String body = """
                {"model":"deepseek-chat","messages":[{"role":"system","content":"You are helpful"},{"role":"user","content":"Hello"}]}""";

        byte[] result = interceptor.injectCacheControl(body.getBytes());
        var root = objectMapper.readTree(result);

        var sysContent = root.get("messages").get(0).get("content");
        assertThat(sysContent.isArray()).isTrue();
        assertThat(sysContent.get(0).get("type").asText()).isEqualTo("text");
        assertThat(sysContent.get(0).get("text").asText()).isEqualTo("You are helpful");
        assertThat(sysContent.get(0).get("cache_control").get("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("用户消息 content 保持不变")
    void shouldNotModifyUserMessages() throws Exception {
        String body = """
                {"model":"deepseek-chat","messages":[{"role":"system","content":"Sys"},{"role":"user","content":"Hello"}]}""";

        byte[] result = interceptor.injectCacheControl(body.getBytes());
        var root = objectMapper.readTree(result);

        var userContent = root.get("messages").get(1).get("content");
        assertThat(userContent.isTextual()).isTrue();
        assertThat(userContent.asText()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("无系统消息时 body 不变")
    void shouldNotModifyWhenNoSystemMessage() throws Exception {
        String body = """
                {"model":"deepseek-chat","messages":[{"role":"user","content":"Hello"}]}""";

        byte[] result = interceptor.injectCacheControl(body.getBytes());
        var root = objectMapper.readTree(result);

        var userContent = root.get("messages").get(0).get("content");
        assertThat(userContent.isTextual()).isTrue();
    }

    @Test
    @DisplayName("无 messages 字段时返回原 body")
    void shouldReturnOriginalWhenNoMessages() throws Exception {
        String body = """
                {"model":"deepseek-chat"}""";

        byte[] result = interceptor.injectCacheControl(body.getBytes());

        assertThat(result).isEqualTo(body.getBytes());
    }

    @Test
    @DisplayName("系统消息 content 已经是数组时跳过")
    void shouldSkipArrayContent() throws Exception {
        String body = """
                {"model":"deepseek-chat","messages":[{"role":"system","content":[{"type":"text","text":"Already array"}]}]}""";

        byte[] result = interceptor.injectCacheControl(body.getBytes());
        var root = objectMapper.readTree(result);

        var sysContent = root.get("messages").get(0).get("content");
        assertThat(sysContent.isArray()).isTrue();
        // content is still the original array, not double-wrapped
        assertThat(sysContent.size()).isEqualTo(1);
    }
}
