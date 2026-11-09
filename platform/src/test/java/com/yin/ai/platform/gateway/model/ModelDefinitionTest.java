package com.yin.ai.platform.gateway.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModelDefinition — 模型定义")
class ModelDefinitionTest {

    @Test
    @DisplayName("Builder 构建完整模型定义")
    void shouldBuildCompleteDefinition() {
        ModelDefinition def = ModelDefinition.builder()
                .name("qwen-plus")
                .provider("dashscope")
                .baseUrl("https://dashscope.aliyuncs.com")
                .apiKey("sk-xxx")
                .maxTokens(8192)
                .costPer1kPromptTokens(0.0005)
                .costPer1kCompletionTokens(0.0015)
                .enabled(true)
                .rateLimitPerMinute(60)
                .metadata("region", "cn-hangzhou")
                .build();

        assertThat(def.getName()).isEqualTo("qwen-plus");
        assertThat(def.getProvider()).isEqualTo("dashscope");
        assertThat(def.getBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(def.getApiKey()).isEqualTo("sk-xxx");
        assertThat(def.getMaxTokens()).isEqualTo(8192);
        assertThat(def.getCostPer1kPromptTokens()).isEqualTo(0.0005);
        assertThat(def.getCostPer1kCompletionTokens()).isEqualTo(0.0015);
        assertThat(def.isEnabled()).isTrue();
        assertThat(def.getRateLimitPerMinute()).isEqualTo(60);
        assertThat(def.getMetadata()).containsEntry("region", "cn-hangzhou");
    }

    @Test
    @DisplayName("name 为空时抛出异常")
    void shouldRejectEmptyName() {
        assertThatThrownBy(() -> ModelDefinition.builder().name("").provider("deepseek").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("provider 为空时抛出异常")
    void shouldRejectEmptyProvider() {
        assertThatThrownBy(() -> ModelDefinition.builder().name("gpt-4").provider("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }

    @Test
    @DisplayName("Builder 默认值生效")
    void shouldUseDefaultValues() {
        ModelDefinition def = ModelDefinition.builder()
                .name("default-model")
                .provider("test-provider")
                .build();

        assertThat(def.getMaxTokens()).isEqualTo(4096);
        assertThat(def.isEnabled()).isTrue();
        assertThat(def.getRateLimitPerMinute()).isEqualTo(0);
        assertThat(def.getCostPer1kPromptTokens()).isEqualTo(0.0);
        assertThat(def.getMetadata()).isEmpty();
    }

    @Test
    @DisplayName("预估成本计算")
    void shouldEstimateCostCorrectly() {
        ModelDefinition def = ModelDefinition.builder()
                .name("test")
                .provider("test")
                .costPer1kPromptTokens(0.01)
                .costPer1kCompletionTokens(0.03)
                .build();

        double cost = def.estimateCost(5000, 2000);
        assertThat(cost).isEqualTo(0.05 + 0.06); // 5000*0.01/1000 + 2000*0.03/1000
    }

    @Test
    @DisplayName("metadata 不可变性")
    void shouldReturnUnmodifiableMetadata() {
        ModelDefinition def = ModelDefinition.builder()
                .name("test")
                .provider("test")
                .metadata("key", "value")
                .build();

        assertThatThrownBy(() -> def.getMetadata().put("new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Builder 多次调用 metadata 累积")
    void shouldAccumulateMetadata() {
        ModelDefinition def = ModelDefinition.builder()
                .name("test")
                .provider("test")
                .metadata("a", 1)
                .metadata("b", 2)
                .build();

        assertThat(def.getMetadata()).hasSize(2);
        assertThat(def.getMetadata()).containsEntry("a", 1);
        assertThat(def.getMetadata()).containsEntry("b", 2);
    }
}
