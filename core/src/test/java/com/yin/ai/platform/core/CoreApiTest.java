package com.yin.ai.platform.core;

import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentResult;
import com.yin.ai.platform.core.agent.DefaultAgentRuntime;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreApiTest {

    @Test
    void contextAndRequestsAreImmutableAndFrameworkNeutral() {
        ExecutionContext context = ExecutionContext.create("trace-1")
                .withCaller("pure-java-example")
                .withAttribute("tenant", "demo");
        AgentRequest request = AgentRequest.of("summarize input", "7", "s-1", context);
        ModelRequest modelRequest = new ModelRequest(List.of(ModelMessage.user("hello")),
                null, null, null);

        assertEquals("pure-java-example", request.executionContext().caller());
        assertEquals("demo", request.executionContext().attributes().get("tenant"));
        assertEquals("hello", modelRequest.messages().getFirst().content());
        assertThrows(UnsupportedOperationException.class,
                () -> request.metadata().put("mutable", "no"));
    }

    @Test
    void defaultToolRegistryRejectsAmbiguousDuplicateNames() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        Tool tool = new EchoTool();
        registry.register(tool);
        registry.register(tool);

        assertTrue(registry.find("echo").isPresent());
        assertEquals("value", registry.find("echo").orElseThrow()
                .execute(new ToolInput(Map.of("text", "value"), null,
                        ExecutionContext.create("trace-2"))).data());
        assertThrows(IllegalStateException.class, () -> registry.register(new EchoTool()));
    }

    @Test
    void defaultRuntimeExecutesWithoutAFrameworkAndUsesTheModelSpi() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new EchoTool());
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse execute(ModelRequest request, ExecutionContext context) {
                captured.set(request);
                return new ModelResponse("pure-java-answer", "stop", null,
                        Map.of("provider", "mock"));
            }

            @Override
            public ModelCall submit(ModelRequest request, ExecutionContext context,
                                    ModelEventListener listener) {
                throw new UnsupportedOperationException();
            }
        };
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(model)
                .tools(registry)
                .build();

        AgentResult result = runtime.execute(AgentRequest.of("hello", "7", "session-1",
                ExecutionContext.create("trace-runtime")));

        assertTrue(result.successful());
        assertEquals("pure-java-answer", result.output());
        assertEquals(List.of("echo"), captured.get().allowedTools());
        assertEquals("hello", captured.get().messages().getFirst().content());
        assertEquals("mock", result.metadata().get("provider"));
    }

    private static final class EchoTool implements Tool {
        private final ToolDefinition definition = new ToolDefinition("echo", "Echo input",
                "1.0.0", Map.of(), Set.of("test"), ToolDefinition.Risk.READ_ONLY);

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolInput input) {
            return ToolResult.success(input.parameters().get("text"));
        }
    }
}
