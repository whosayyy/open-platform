package com.yin.ai.platform.gateway.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultModelRegistry — 模型注册中心")
class DefaultModelRegistryTest {

    private DefaultModelRegistry registry;
    private ModelDefinition deepseekChat;
    private ModelDefinition qwenPlus;
    private ModelDefinition gpt4;

    @BeforeEach
    void setUp() {
        deepseekChat = ModelDefinition.builder()
                .name("deepseek-chat")
                .provider("deepseek")
                .baseUrl("https://api.deepseek.com")
                .apiKey("sk-ds-xxx")
                .maxTokens(4096)
                .costPer1kPromptTokens(0.00014)
                .costPer1kCompletionTokens(0.00028)
                .enabled(true)
                .build();

        qwenPlus = ModelDefinition.builder()
                .name("qwen-plus")
                .provider("dashscope")
                .baseUrl("https://dashscope.aliyuncs.com")
                .apiKey("sk-qw-xxx")
                .maxTokens(8192)
                .costPer1kPromptTokens(0.0005)
                .costPer1kCompletionTokens(0.0015)
                .enabled(true)
                .rateLimitPerMinute(60)
                .build();

        gpt4 = ModelDefinition.builder()
                .name("gpt-4")
                .provider("openai")
                .baseUrl("https://api.openai.com")
                .apiKey("sk-oai-xxx")
                .maxTokens(8192)
                .costPer1kPromptTokens(0.03)
                .costPer1kCompletionTokens(0.06)
                .enabled(false)
                .build();

        registry = new DefaultModelRegistry(
                List.of(deepseekChat, qwenPlus, gpt4),
                "deepseek-chat");
    }

    @Test
    @DisplayName("初始化后包含所有注册模型")
    void shouldContainAllRegisteredModels() {
        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.listAll()).hasSize(3);
    }

    @Test
    @DisplayName("获取默认模型")
    void shouldReturnDefaultModel() {
        ModelDefinition def = registry.getDefault();
        assertThat(def.getName()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("按名称获取模型")
    void shouldReturnModelByName() {
        assertThat(registry.get("qwen-plus")).isPresent();
        assertThat(registry.get("qwen-plus").get().getProvider()).isEqualTo("dashscope");
    }

    @Test
    @DisplayName("不存在的模型返回 empty")
    void shouldReturnEmptyForUnknownModel() {
        assertThat(registry.get("non-existent")).isEmpty();
    }

    @Test
    @DisplayName("listEnabled 仅返回已启用模型")
    void shouldFilterEnabledModels() {
        List<ModelDefinition> enabled = registry.listEnabled();
        assertThat(enabled).hasSize(2);
        assertThat(enabled).extracting(ModelDefinition::getName)
                .containsExactlyInAnyOrder("deepseek-chat", "qwen-plus");
    }

    @Test
    @DisplayName("supports 仅对已启用模型返回 true")
    void shouldSupportOnlyEnabledModels() {
        assertThat(registry.supports("deepseek-chat")).isTrue();
        assertThat(registry.supports("qwen-plus")).isTrue();
        assertThat(registry.supports("gpt-4")).isFalse();
        assertThat(registry.supports("unknown")).isFalse();
    }

    @Test
    @DisplayName("listByProvider 按 provider 过滤")
    void shouldFilterByProvider() {
        List<ModelDefinition> dashscope = registry.listByProvider("dashscope");
        assertThat(dashscope).hasSize(1);
        assertThat(dashscope.get(0).getName()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("listByProvider 大小写不敏感")
    void shouldMatchCaseInsensitiveProvider() {
        List<ModelDefinition> result = registry.listByProvider("DeepSeek");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("动态注册新模型")
    void shouldDynamicallyRegisterModel() {
        ModelDefinition newModel = ModelDefinition.builder()
                .name("claude-4")
                .provider("anthropic")
                .enabled(true)
                .build();

        registry.register(newModel);
        assertThat(registry.size()).isEqualTo(4);
        assertThat(registry.get("claude-4")).isPresent();
    }

    @Test
    @DisplayName("注销模型")
    void shouldUnregisterModel() {
        registry.unregister("gpt-4");
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("gpt-4")).isEmpty();
    }

    @Test
    @DisplayName("切换默认模型")
    void shouldChangeDefaultModel() {
        registry.setDefaultModelName("qwen-plus");
        assertThat(registry.getDefault().getName()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("默认模型不可用时回退到第一个启用的模型")
    void shouldFallbackWhenDefaultDisabled() {
        // 禁用默认模型
        registry.unregister("deepseek-chat");
        registry.register(ModelDefinition.builder()
                .name("deepseek-chat")
                .provider("deepseek")
                .enabled(false)
                .build());

        // 默认仍指向已禁用的 deepseek-chat，应回退到 qwen-plus
        assertThat(registry.getDefault().getName()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("无可用模型时抛出异常")
    void shouldThrowWhenNoEnabledModel() {
        DefaultModelRegistry emptyRegistry = new DefaultModelRegistry(
                List.of(ModelDefinition.builder().name("disabled").provider("x").enabled(false).build()),
                "disabled");

        assertThatThrownBy(emptyRegistry::getDefault)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No enabled model found");
    }

    @Test
    @DisplayName("构造函数验证默认模型名称非空")
    void shouldRejectBlankDefaultModelName() {
        assertThatThrownBy(() -> new DefaultModelRegistry(List.of(deepseekChat), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultModelName");
    }

    @Test
    @DisplayName("definitions 返回不可修改视图")
    void shouldReturnUnmodifiableDefinitions() {
        assertThatThrownBy(() -> registry.definitions().put("new", deepseekChat))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("listAll 按名称排序")
    void shouldSortByName() {
        List<ModelDefinition> all = registry.listAll();
        assertThat(all).extracting(ModelDefinition::getName)
                .containsExactly("deepseek-chat", "gpt-4", "qwen-plus");
    }
}
