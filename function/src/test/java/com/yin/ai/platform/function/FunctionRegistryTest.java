package com.yin.ai.platform.function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FunctionRegistry 单元测试 — 模拟 Function Bean 验证注册/查找/调用。
 */
@DisplayName("FunctionRegistry")
class FunctionRegistryTest {

    private FunctionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FunctionRegistry();
    }

    private Function mockFunction(String name, String category, boolean callableByLLM) {
        FunctionDefinition def = FunctionDefinition.builder()
                .name(name)
                .description("Description for " + name)
                .category(category)
                .risk(FunctionDefinition.Risk.READ_ONLY)
                .callableByLLM(callableByLLM)
                .parameters(List.of())
                .build();

        Function fn = mock(Function.class);
        when(fn.getDefinition()).thenReturn(def);
        when(fn.execute(any(FunctionInput.class)))
                .thenReturn(FunctionResult.builder().success(true).data(name + "_result").build());
        return fn;
    }

    @Test
    @DisplayName("注册后可通过名称获取")
    void shouldRetrieveRegisteredFunction() {
        Function fn = mockFunction("searchNews", "search", true);
        registry.register(fn);

        Optional<Function> found = registry.get("searchNews");
        assertThat(found).isPresent();
        assertThat(found.get().getDefinition().getName()).isEqualTo("searchNews");
    }

    @Test
    @DisplayName("获取不存在的 Function 返回 empty")
    void shouldReturnEmptyForUnknownFunction() {
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("重复注册抛出 IllegalStateException")
    void shouldRejectDuplicateRegistration() {
        Function fn1 = mockFunction("searchNews", "search", true);
        Function fn2 = mockFunction("searchNews", "search", true);

        registry.register(fn1);
        assertThatThrownBy(() -> registry.register(fn2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("searchNews");
    }

    @Test
    @DisplayName("getDefinition 返回正确的元数据")
    void shouldReturnCorrectDefinition() {
        Function fn = mockFunction("searchNews", "search", true);
        registry.register(fn);

        Optional<FunctionDefinition> def = registry.getDefinition("searchNews");
        assertThat(def).isPresent();
        assertThat(def.get().getName()).isEqualTo("searchNews");
        assertThat(def.get().getCategory()).isEqualTo("search");
    }

    @Test
    @DisplayName("getFunctionNames 返回所有已注册名称")
    void shouldReturnAllRegisteredNames() {
        registry.register(mockFunction("searchNews", "search", true));
        registry.register(mockFunction("getHotContents", "query", true));

        assertThat(registry.getFunctionNames()).containsExactlyInAnyOrder("searchNews", "getHotContents");
    }

    @Test
    @DisplayName("invoke 执行 Function 并返回结果")
    void shouldInvokeFunction() {
        Function fn = mockFunction("test", "test", true);
        registry.register(fn);

        FunctionResult result = registry.invoke("test", Map.of("key", "value"));
        System.out.println(result);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("test_result");
    }

    @Test
    @DisplayName("invoke 不存在的 Function 抛出异常")
    void shouldThrowForUnknownInvocation() {
        assertThatThrownBy(() -> registry.invoke("unknown", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invokeAll 并行调用多个 Function")
    void shouldInvokeAllInParallel() {
        registry.register(mockFunction("f1", "test", true));
        registry.register(mockFunction("f2", "test", true));
        registry.register(mockFunction("f3", "test", true));

        Map<String, Map<String, Object>> calls = Map.of(
                "f1", Map.of(),
                "f2", Map.of(),
                "f3", Map.of()
        );

        Map<String, FunctionResult> results = registry.invokeAll(calls);
        assertThat(results).hasSize(3);
        assertThat(results.get("f1").getData()).isEqualTo("f1_result");
        assertThat(results.get("f2").getData()).isEqualTo("f2_result");
        assertThat(results.get("f3").getData()).isEqualTo("f3_result");
    }

    @Test
    @DisplayName("getLLMCallableToolCallbacks 只返回 callableByLLM=true 的 Function")
    void shouldFilterLLMCallableFunctions() {
        registry.register(mockFunction("searchNews", "search", true));
        registry.register(mockFunction("getHotContents", "query", true));
        registry.register(mockFunction("publishContent", "publish", false));

        List<ToolCallback> callbacks = registry.getLLMCallableToolCallbacks();
        assertThat(callbacks).hasSize(2);
    }

    @Test
    @DisplayName("getToolCallbacks 按名称过滤")
    void shouldGetToolCallbacksByNames() {
        registry.register(mockFunction("f1", "test", true));
        registry.register(mockFunction("f2", "test", true));
        registry.register(mockFunction("f3", "test", true));

        List<ToolCallback> callbacks = registry.getToolCallbacks(List.of("f1", "f3"));
        assertThat(callbacks).hasSize(2);
    }

    @Test
    @DisplayName("getToolCallbacks 空/null 列表返回空")
    void shouldReturnEmptyForNullOrEmptyNames() {
        assertThat(registry.getToolCallbacks(null)).isEmpty();
        assertThat(registry.getToolCallbacks(List.of())).isEmpty();
    }

    @Test
    @DisplayName("未分级函数默认不得暴露给 LLM")
    void shouldFailClosedForUnspecifiedRisk() {
        FunctionDefinition def = FunctionDefinition.builder()
                .name("unsafeUnknown").description("unknown risk")
                .callableByLLM(true).parameters(List.of()).build();
        Function fn = mock(Function.class);
        when(fn.getDefinition()).thenReturn(def);
        registry.register(fn);

        assertThat(registry.getLLMCallableToolCallbacks()).isEmpty();
    }

    @Test
    @DisplayName("动态来源原子替换且不得覆盖内部函数")
    void shouldReplaceDynamicSourceWithoutOverwritingStaticFunction() {
        registry.register(mockFunction("internalTool", "query", true));
        Function remote = mockFunction("mcp.research.search", "mcp", true);
        remote.getDefinition().setOrigin(FunctionDefinition.Origin.MCP);
        remote.getDefinition().setSourceId("mcp:research");

        registry.replaceSource("mcp:research", List.of(remote));
        assertThat(registry.getSourceFunctionNames("mcp:research"))
                .containsExactly("mcp.research.search");

        Function collision = mockFunction("internalTool", "mcp", true);
        collision.getDefinition().setOrigin(FunctionDefinition.Origin.MCP);
        collision.getDefinition().setSourceId("mcp:research");
        assertThatThrownBy(() -> registry.replaceSource("mcp:research", List.of(collision)))
                .isInstanceOf(IllegalStateException.class);
    }
}
