package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentResult;
import com.yin.ai.platform.core.agent.DefaultAgentRuntime;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelToolCall;
import com.yin.ai.platform.core.model.ModelUsage;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolRegistry;
import com.yin.ai.platform.core.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具 schema 必须到达模型适配器。
 * <p>
 * 历史缺陷：{@code ModelRequest} 只携带工具<b>名字</b>，不携带 {@code ToolDefinition}。
 * 后果是消费方写的模型适配器无法把工具的参数结构告诉模型，只能自己额外持有
 * {@code ToolRegistry} 去查——这是被真实消费者（examples/code-qa）逼出来的缺口。
 *
 * <p>本测试锁定的契约：**平台编排在调用模型时必须提供完整的工具定义**，
 * 适配器不需要也不应该自己去查注册表。
 */
@DisplayName("工具 schema 传递契约")
class ToolDefinitionPropagationTest {

    private static final String SCHEMA_DESCRIPTION = "Keywords to search for, e.g. 'retry timeout'";

    /** 记录模型实际收到的请求。 */
    private final AtomicReference<ModelRequest> captured = new AtomicReference<>();

    @Test
    @DisplayName("ReAct 调用模型时携带完整 ToolDefinition（含 inputSchema）")
    void reactPatternSendsFullToolDefinitions() {
        ToolRegistry tools = new DefaultToolRegistry();
        tools.register(searchTool());

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(new SchemaCapturingModelClient(captured))
                .tools(tools)
                .build();

        AgentResult result = runtime.execute(AgentRequest.of(
                "引擎超时怎么实现的？", "user-1", "session-1",
                ExecutionContext.create("trace-schema")));

        assertThat(result.successful()).isTrue();

        ModelRequest request = captured.get();
        assertThat(request).as("模型必须被调用过").isNotNull();

        // 核心断言：schema 必须随请求到达适配器
        assertThat(request.hasToolDefinitions()).isTrue();
        assertThat(request.toolDefinitions()).hasSize(1);

        ToolDefinition definition = request.toolDefinitions().get(0);
        assertThat(definition.name()).isEqualTo("search_code");
        assertThat(definition.inputSchema()).isNotEmpty();
        assertThat(definition.inputSchema()).containsEntry("type", "object");
        assertThat(definition.inputSchema().toString()).contains(SCHEMA_DESCRIPTION);

        // 兼容字段仍然可用，且与定义保持一致
        assertThat(request.allowedTools()).containsExactly("search_code");
        assertThat(request.toolDefinition("search_code")).contains(definition);
    }

    @Test
    @DisplayName("未注册工具时工具定义为空，不影响普通调用")
    void noToolsMeansNoDefinitions() {
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(new SchemaCapturingModelClient(captured))
                .tools(new DefaultToolRegistry())
                .build();

        AgentResult result = runtime.execute(AgentRequest.of(
                "普通问题", "user-1", "session-1",
                ExecutionContext.create("trace-no-tools")));

        assertThat(result.successful()).isTrue();
        assertThat(captured.get().hasToolDefinitions()).isFalse();
        assertThat(captured.get().allowedTools()).isEmpty();
    }

    @Test
    @DisplayName("只给工具名的兼容构造仍可用，且 allowedTools 被自动派生")
    void nameOnlyCompatibilityConstructorStillWorks() {
        ModelRequest request = new ModelRequest(
                List.of(com.yin.ai.platform.core.model.ModelMessage.user("hi")),
                com.yin.ai.platform.core.model.ModelOptions.defaults(),
                List.of("echo", "translate"),
                Map.of());

        assertThat(request.allowedTools()).containsExactly("echo", "translate");
        assertThat(request.hasToolDefinitions()).isFalse();
        assertThat(request.toolDefinition("echo")).isEmpty();
    }

    @Test
    @DisplayName("只传 definitions 时 allowedTools 自动派生，避免两处不一致")
    void allowedToolsDerivedFromDefinitions() {
        ToolDefinition definition = searchTool().definition();

        ModelRequest request = new ModelRequest(
                List.of(com.yin.ai.platform.core.model.ModelMessage.user("hi")),
                com.yin.ai.platform.core.model.ModelOptions.defaults(),
                List.of(),
                List.of(definition),
                Map.of());

        assertThat(request.allowedTools()).containsExactly("search_code");
        assertThat(request.hasToolDefinitions()).isTrue();
    }

    // ---------------- 测试替身 ----------------

    /** 第一次返回工具调用，第二次返回最终答案；每次都记录请求。 */
    private static final class SchemaCapturingModelClient implements ModelClient {

        private final AtomicReference<ModelRequest> captured;
        private int calls;

        private SchemaCapturingModelClient(AtomicReference<ModelRequest> captured) {
            this.captured = captured;
        }

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            captured.set(request);
            calls++;
            if (calls == 1) {
                return new ModelResponse("", "tool_calls", ModelUsage.empty(), Map.of(),
                        List.of(new ModelToolCall("call-1", "search_code",
                                Map.of("query", "engine timeout"))));
            }
            return new ModelResponse("最终答案", "stop", ModelUsage.empty(), Map.of());
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }

    private static Tool searchTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "search_code",
                        "Search the code knowledge base",
                        "1.0.0",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("query", Map.of(
                                        "type", "string",
                                        "description", SCHEMA_DESCRIPTION)),
                                "required", List.of("query")),
                        Set.of("code"),
                        ToolDefinition.Risk.READ_ONLY);
            }

            @Override
            public ToolResult execute(ToolInput input) {
                return ToolResult.success("### 执行引擎超时\nFuture.get(timeout)");
            }
        };
    }
}
