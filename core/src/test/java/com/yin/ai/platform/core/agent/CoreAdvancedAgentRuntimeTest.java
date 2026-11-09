package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelToolCall;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import com.yin.ai.platform.core.tool.Tool;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolInput;
import com.yin.ai.platform.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAdvancedAgentRuntimeTest {

    @Test
    void reactExecutesProviderNeutralToolCallsUntilTheModelStops() {
        QueueModel model = new QueueModel(
                new ModelResponse("", "tool_calls", null, Map.of(),
                        List.of(new ModelToolCall("call-1", "echo", Map.of("text", "news")))),
                new ModelResponse("react complete", "stop", null, Map.of("provider", "test")));
        AtomicInteger toolCalls = new AtomicInteger();
        DefaultToolRegistry tools = registry(toolCalls);
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder().model(model).tools(tools).build();

        AgentResult result = runtime.execute(request("REACT"));

        assertTrue(result.successful());
        assertEquals("react complete", result.output());
        assertEquals("REACT", result.metadata().get("mode"));
        assertEquals(1, toolCalls.get());
        assertEquals(2, model.requests.size());
        assertTrue(model.requests.get(1).messages().stream()
                .anyMatch(message -> message.role().name().equals("TOOL")
                        && message.content().equals("news")));
    }

    @Test
    void planExecuteGeneratesAPlanRunsDependenciesAndSynthesizes() {
        QueueModel model = new QueueModel(
                response("""
                        Planning notes.
                        {"steps":[
                          {"id":"collect","description":"Collect input","executor":"echo",
                           "arguments":{"text":"evidence"},"dependsOn":[]},
                          {"id":"draft","description":"Draft answer","executor":"llm",
                           "dependsOn":["collect"]}
                        ]}
                        """),
                response("draft from evidence"),
                response("final planned answer"));
        AtomicInteger toolCalls = new AtomicInteger();
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder().model(model)
                .tools(registry(toolCalls)).build();

        AgentResult result = runtime.execute(request("PLAN_EXECUTE"));

        assertTrue(result.successful());
        assertEquals("final planned answer", result.output());
        assertEquals("PLAN_EXECUTE", result.metadata().get("mode"));
        assertEquals(1, toolCalls.get());
        assertEquals(3, model.requests.size());
        assertTrue(model.requests.get(1).messages().getLast().content().contains("evidence"));
    }

    @Test
    void deepResearchRunsPlanningExecutionAndDedicatedSynthesisInCore() {
        QueueModel model = new QueueModel(
                response("""
                        {"action":"clarified","goal":"precise answer request"}
                        """),
                response("""
                        {"steps":[{"id":"research","description":"Gather sources",
                        "executor":"echo","arguments":{"text":"source-a https://example.com/source"},"dependsOn":[]}]}
                        """),
                response("{\"decision\":\"CONTINUE\"}"),
                response("# Research report\nGrounded in source-a https://example.com/source "
                        + "and invented https://fake.example/source"));
        AtomicInteger toolCalls = new AtomicInteger();
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder().model(model)
                .tools(registry(toolCalls)).build();

        AgentResult result = runtime.execute(request("DEEP_RESEARCH"));

        assertTrue(result.successful());
        assertTrue(result.output().contains("https://example.com/source"));
        assertTrue(result.output().contains("[unverified source removed]"));
        assertTrue(!result.output().contains("https://fake.example/source"));
        assertEquals("DEEP_RESEARCH", result.metadata().get("mode"));
        assertEquals("completed", result.metadata().get("phase"));
        assertEquals(1, result.metadata().get("verifiedSourceCount"));
        assertEquals(1, result.metadata().get("removedUnverifiedSourceCount"));
        assertEquals(1, toolCalls.get());
        assertEquals(4, model.requests.size());
        assertEquals("agent.research.clarify",
                model.requests.getFirst().metadata().get("operation"));
        assertEquals("agent.plan.reflect",
                model.requests.get(2).metadata().get("operation"));
        assertTrue(model.requests.getLast().messages().getLast().content().contains("source-a"));
    }

    private static AgentRequest request(String mode) {
        return new AgentRequest("answer the request", "user-1", "session-1",
                Map.of("forcedMode", mode), null, null, null,
                ExecutionContext.create("trace-1"));
    }

    private static ModelResponse response(String content) {
        return new ModelResponse(content, "stop", null, Map.of());
    }

    private static DefaultToolRegistry registry(AtomicInteger calls) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new Tool() {
            private final ToolDefinition definition = new ToolDefinition("echo", "Echo text",
                    "1.0.0", Map.of(), Set.of(), ToolDefinition.Risk.READ_ONLY);

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolInput input) {
                calls.incrementAndGet();
                return ToolResult.success(input.parameters().get("text"));
            }
        });
        return registry;
    }

    private static final class QueueModel implements ModelClient {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        private final List<ModelRequest> requests = new ArrayList<>();

        private QueueModel(ModelResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            requests.add(request);
            ModelResponse response = responses.poll();
            if (response == null) throw new AssertionError("Unexpected model call");
            return response;
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
