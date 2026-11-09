package com.yin.ai.platform.prompt;

import com.yin.ai.platform.prompt.model.VariableDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VariableRegistry 单元测试 — 验证从 variables.yaml 加载和查询变量定义。
 */
@DisplayName("VariableRegistry")
class VariableRegistryTest {

    private VariableRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new VariableRegistry();
        registry.load();
    }

    @Test
    @DisplayName("加载 variables.yaml 后 registry 非空")
    void shouldLoadVariablesFromYaml() {
        assertThat(registry.size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("查询已存在的变量 currentTime")
    void shouldFindCurrentTimeVariable() {
        Optional<VariableDef> def = registry.get("currentTime");
        assertThat(def).isPresent();
        assertThat(def.get().getName()).isEqualTo("currentTime");
        assertThat(def.get().getType()).isEqualTo("datetime");
        assertThat(def.get().getSource()).isEqualTo("system");
    }

    @Test
    @DisplayName("查询已存在的变量 conversationContext")
    void shouldFindConversationContextVariable() {
        Optional<VariableDef> def = registry.get("conversationContext");
        assertThat(def).isPresent();
        assertThat(def.get().getSource()).isEqualTo("memory");
        assertThat(def.get().isCacheable()).isFalse();
    }

    @Test
    @DisplayName("查询已存在的变量 ragContext")
    void shouldFindRagContextVariable() {
        Optional<VariableDef> def = registry.get("ragContext");
        assertThat(def).isPresent();
        assertThat(def.get().getSource()).isEqualTo("knowledge");
    }

    @Test
    @DisplayName("查询已存在的变量 tools")
    void shouldFindToolsVariable() {
        Optional<VariableDef> def = registry.get("tools");
        assertThat(def).isPresent();
        assertThat(def.get().getType()).isEqualTo("list");
        assertThat(def.get().isCacheable()).isTrue();
    }

    @Test
    @DisplayName("查询不存在的变量返回 empty")
    void shouldReturnEmptyForUnknownVariable() {
        assertThat(registry.get("unknownVar")).isEmpty();
    }

    @Test
    @DisplayName("listAll 返回所有变量")
    void shouldListAllVariables() {
        var all = registry.listAll();
        assertThat(all).isNotEmpty();
        assertThat(all.stream().map(VariableDef::getName))
                .contains("currentTime", "conversationContext", "ragContext", "tools");
    }

    @Test
    @DisplayName("refresh 清空并重新加载")
    void shouldRefreshRegistry() {
        int before = registry.size();
        registry.refresh();
        assertThat(registry.size()).isEqualTo(before);
    }
}
