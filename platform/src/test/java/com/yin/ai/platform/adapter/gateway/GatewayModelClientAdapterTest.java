package com.yin.ai.platform.adapter.gateway;

import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelEvent;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelOptions;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import com.yin.ai.platform.engine.stream.StreamPacket;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayModelClientAdapterTest {

    @Test
    void preservesMessagesOptionsToolsAndExecutionContext() {
        AtomicReference<GatewayRequest> captured = new AtomicReference<>();
        UnifiedChatClient gateway = new StubGateway(request -> {
            captured.set(request);
            GatewayResponse response = new GatewayResponse("answer");
            response.setFinishReason("stop");
            response.setModel("resolved-model");
            response.setTraceId("trace-1");
            response.setTokenUsage(new TokenUsage(12, 5, 17));
            response.setMetadata(Map.of("provider", "test"));
            return response;
        }, Flux.empty());
        GatewayModelClientAdapter adapter = new GatewayModelClientAdapter(gateway);
        ModelRequest request = new ModelRequest(
                List.of(ModelMessage.system("rules"), ModelMessage.user("question")),
                new ModelOptions("requested-model", 0.25, 512, Map.of()),
                List.of("search", "read"),
                Map.of("promptVersion", "v3"));
        ExecutionContext context = new ExecutionContext("trace-1", "session-1", "tenant-1",
                "user-1", "agent", "answer", null,
                Map.of("roles", List.of("editor"), "permissions", Set.of("article:read")), null);

        ModelResponse response = adapter.execute(request, context);

        GatewayRequest gatewayRequest = captured.get();
        assertThat(gatewayRequest.getMessages()).extracting(message -> message.getRole().name())
                .containsExactly("system", "user");
        assertThat(gatewayRequest.getOptions().getModel()).isEqualTo("requested-model");
        assertThat(gatewayRequest.getOptions().getTemperature()).isEqualTo(0.25);
        assertThat(gatewayRequest.getOptions().getMaxTokens()).isEqualTo(512);
        assertThat(gatewayRequest.getFunctionNames()).containsExactlyInAnyOrder("search", "read");
        assertThat(gatewayRequest.getTraceId()).isEqualTo("trace-1");
        assertThat(gatewayRequest.getTenantId()).isEqualTo("tenant-1");
        assertThat(gatewayRequest.getUserId()).isEqualTo("user-1");
        assertThat(gatewayRequest.getSessionId()).isEqualTo("session-1");
        assertThat(gatewayRequest.getRoles()).containsExactly("editor");
        assertThat(gatewayRequest.getPermissions()).containsExactly("article:read");
        assertThat(gatewayRequest.getPromptVersion()).isEqualTo("v3");
        assertThat(response.content()).isEqualTo("answer");
        assertThat(response.usage().inputTokens()).isEqualTo(12);
        assertThat(response.usage().outputTokens()).isEqualTo(5);
        assertThat(response.metadata()).containsEntry("model", "resolved-model")
                .containsEntry("provider", "test");
    }

    @Test
    void normalizesGatewayToolCallsBeforeTheyEnterCore() {
        UnifiedChatClient gateway = new StubGateway(request -> {
            GatewayResponse response = new GatewayResponse("");
            response.setToolCalls(List.of(new GatewayResponse.ToolCall(
                    "call-1", "search", "{\"query\":\"platform core\"}")));
            return response;
        }, Flux.empty());

        ModelResponse response = new GatewayModelClientAdapter(gateway)
                .execute(simpleRequest(), ExecutionContext.create("trace-tools"));

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().name()).isEqualTo("search");
        assertThat(response.toolCalls().getFirst().arguments())
                .containsEntry("query", "platform core");
        assertThat(response.metadata().get("toolCalls")).isEqualTo(response.toolCalls());
    }

    @Test
    void exposesGatewayStreamAsProviderNeutralEventsAndCompletion() throws Exception {
        StreamPacket first = StreamPacket.builder().type("text").content("hel")
                .sequence(1).traceId("trace-stream").build();
        StreamPacket second = StreamPacket.builder().type("text").content("lo")
                .sequence(2).traceId("trace-stream").build();
        StreamPacket complete = StreamPacket.builder().type("complete").content("hello")
                .sequence(3).traceId("trace-stream").build();
        UnifiedChatClient gateway = new StubGateway(request -> null,
                Flux.just(first, second, complete));
        GatewayModelClientAdapter adapter = new GatewayModelClientAdapter(gateway);
        List<ModelEvent> events = new ArrayList<>();

        ModelCall call = adapter.submit(simpleRequest(), ExecutionContext.create("trace-stream"),
                events::add);
        ModelResponse response = call.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThat(events).extracting(ModelEvent::type)
                .containsExactly("text", "text", "complete");
        assertThat(events.get(0).metadata()).containsEntry("sequence", 1L);
        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.metadata()).containsEntry("streaming", true)
                .containsEntry("traceId", "trace-stream");
    }

    @Test
    void cancellationDisposesAnActiveStream() {
        GatewayModelClientAdapter adapter = new GatewayModelClientAdapter(
                new StubGateway(request -> null, Flux.never()));
        ModelCall call = adapter.submit(simpleRequest(), ExecutionContext.create("trace-cancel"), null);

        assertThat(call.cancel()).isTrue();
        assertThat(call.cancel()).isFalse();
        assertThatThrownBy(() -> call.completion().toCompletableFuture().join())
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    private static ModelRequest simpleRequest() {
        return new ModelRequest(List.of(ModelMessage.user("hello")), null, null, null);
    }

    private interface BlockingHandler {
        GatewayResponse apply(GatewayRequest request);
    }

    private record StubGateway(BlockingHandler handler,
                               Flux<StreamPacket> packets) implements UnifiedChatClient {
        @Override
        public GatewayResponse chat(GatewayRequest request) {
            return handler.apply(request);
        }

        @Override
        public Flux<StreamPacket> chatStream(GatewayRequest request) {
            return packets;
        }
    }
}
