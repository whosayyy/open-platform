package com.yin.ai.platform.example;

import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentResult;
import com.yin.ai.platform.core.agent.DefaultAgentRuntime;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;

/** Minimal consumer proving that core API usage does not require Spring. */
public final class PureJavaExample {

    private PureJavaExample() {
    }

    public static void main(String[] args) {
        ExecutionContext context = ExecutionContext.create("pure-java-trace")
                .withCaller("pure-java-example")
                .withOperation("smoke-test");
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ModelClient model = new MockModelClient();
        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .model(model)
                .tools(registry)
                .build();
        AgentRequest request = AgentRequest.of("Demonstrate the platform core", "local", "demo", context);
        AgentResult result = runtime.execute(request);

        if (!"pure-java-example".equals(request.executionContext().caller())
                || !registry.definitions().isEmpty()
                || !result.successful()
                || !"Mock response: Demonstrate the platform core".equals(result.output())) {
            throw new IllegalStateException("Core smoke test failed");
        }
        assertSpringIsAbsent();
        System.out.println("PURE_JAVA_PLATFORM_OK: " + result.output());
    }

    private static void assertSpringIsAbsent() {
        try {
            Class.forName("org.springframework.context.ApplicationContext");
            throw new IllegalStateException("Spring must not be present on the pure Java runtime classpath");
        } catch (ClassNotFoundException expected) {
            // Expected: wkc-ai-platform infrastructure dependencies are optional.
        }
    }

    private static final class MockModelClient implements ModelClient {
        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            String prompt = request.messages().getLast().content();
            return new ModelResponse("Mock response: " + prompt, "stop", null, null);
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new UnsupportedOperationException("Streaming is not needed by this example");
        }
    }
}
