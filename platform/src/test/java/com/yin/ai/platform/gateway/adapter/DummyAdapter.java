package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用 ModelAdapter — 返回预设响应，用于验证适配器契约和 Gateway Filter Chain 集成。
 */
public class DummyAdapter implements ModelAdapter {

    private final String name;
    private final Set<String> supportedModels;
    private final GatewayResponse cannedResponse;
    private final Flux<StreamPacket> cannedStream;
    private final String simulatedError;
    /** 记录最近一次调用的请求 */
    private final CopyOnWriteArrayList<GatewayRequest> recordedCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger(0);

    private DummyAdapter(Builder builder) {
        this.name = builder.name;
        this.supportedModels = builder.supportedModels;
        this.cannedResponse = builder.cannedResponse;
        this.cannedStream = builder.cannedStream;
        this.simulatedError = builder.simulatedError;
    }

    @Override
    public String name() { return name; }

    @Override
    public Set<String> supportedModels() { return supportedModels; }

    @Override
    public GatewayResponse call(GatewayRequest request) {
        callCount.incrementAndGet();
        recordedCalls.add(request);
        if (simulatedError != null) {
            throw new RuntimeException(simulatedError);
        }
        return cannedResponse != null ? cannedResponse : new GatewayResponse("");
    }

    @Override
    public Flux<StreamPacket> stream(GatewayRequest request) {
        callCount.incrementAndGet();
        recordedCalls.add(request);
        if (simulatedError != null) {
            return Flux.error(new RuntimeException(simulatedError));
        }
        return cannedStream != null ? cannedStream : Flux.empty();
    }

    public int getCallCount() { return callCount.get(); }
    public GatewayRequest getLastRequest() {
        return recordedCalls.isEmpty() ? null : recordedCalls.get(recordedCalls.size() - 1);
    }
    public void reset() { callCount.set(0); recordedCalls.clear(); }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name = "dummy";
        private Set<String> supportedModels = Set.of("dummy-model");
        private GatewayResponse cannedResponse;
        private Flux<StreamPacket> cannedStream;
        private String simulatedError;

        public Builder name(String v) { this.name = v; return this; }
        public Builder supportedModels(Set<String> v) { this.supportedModels = v; return this; }
        public Builder cannedResponse(GatewayResponse v) { this.cannedResponse = v; return this; }
        public Builder cannedStream(Flux<StreamPacket> v) { this.cannedStream = v; return this; }
        public Builder simulatedError(String v) { this.simulatedError = v; return this; }

        public DummyAdapter build() { return new DummyAdapter(this); }
    }
}
