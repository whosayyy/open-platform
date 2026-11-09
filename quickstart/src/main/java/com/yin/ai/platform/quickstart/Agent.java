package com.yin.ai.platform.quickstart;

import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentResult;
import com.yin.ai.platform.core.agent.AgentRuntime;
import com.yin.ai.platform.core.agent.DefaultAgentRuntime;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelOptions;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import com.yin.ai.platform.core.tool.ToolRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 产品门面 —— 业务开发者唯一需要认识的类型。
 *
 * <pre>{@code
 * Agent agent = Agent.of("内容审核")
 *         .prompt("判断内容是否违规，只回答 PASS 或 REJECT，并给出一句理由。")
 *         .build();
 *
 * Result result = agent.run(article);
 * }</pre>
 *
 * <p>这个类存在的唯一目的，是把平台内部的编排模式、执行上下文、模型端口、
 * 工具注册表全部藏起来。它刻意保持很薄：一旦它开始变厚，说明下面的抽象漏了。
 *
 * <p>需要的三个平台概念全部有默认值：模型来自 {@link ModelClients}（Spring 应用
 * 由自动配置注入），工具注册表为空，traceId 自动生成。业务代码不需要知道任何一个。
 */
public final class Agent {

    private final String name;
    private final String prompt;
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final AgentRuntime runtime;
    private final Executor executor;

    private Agent(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.prompt = Objects.requireNonNull(builder.prompt, "prompt");
        this.modelClient = builder.modelClient;
        this.toolRegistry = builder.toolRegistry != null
                ? builder.toolRegistry : new DefaultToolRegistry();
        this.executor = builder.executor;
        this.runtime = builder.runtime;
    }

    /** 以业务名称创建一个 Agent，例如「内容审核」「客服问答」。 */
    public static Builder of(String name) {
        return new Builder(name);
    }

    /** 同步执行：给一段业务输入，拿回一个 {@link Result}。 */
    public Result run(String input) {
        try {
            AgentRuntime target = resolveRuntime();
            AgentResult result = target.execute(request(input));
            if (result.successful()) {
                return Result.success(result.output(), result.metadata());
            }
            String error = result.error();
            return Result.failure(error == null || error.isBlank()
                    ? "agent finished with status " + result.status() : error);
        } catch (RuntimeException e) {
            return Result.failure(e);
        }
    }

    /**
     * 流式执行：增量文本边生成边返回。
     * <p>
     * 每个 {@link Result} 是一段增量（{@link Result#delta()} 为 true），
     * 订阅完成即代表整段输出结束。业务侧不需要接触 {@code Flux} 之外的平台类型。
     */
    public Flux<Result> stream(String input) {
        ModelClient client = resolveModelClient();
        return Flux.create(sink -> {
            try {
                ModelRequest request = new ModelRequest(
                        messages(input), options(), java.util.List.of(),
                        java.util.Map.of("agentName", name));
                ModelEventListener listener = event -> {
                    String delta = event.delta();
                    if (delta != null && !delta.isEmpty()) {
                        sink.next(Result.chunk(delta));
                    }
                };
                ModelCall call = client.submit(request, ExecutionContext.create(traceId()), listener);
                if (call == null) {
                    emitBlockingFallback(client, request, sink);
                    return;
                }
                call.completion().whenComplete((response, error) -> {
                    if (error != null) {
                        sink.next(Result.failure(error));
                    } else if (response != null && !response.content().isEmpty()
                            && !sink.isCancelled()) {
                        // 部分实现只在 completion 里给出完整文本，补齐为最后一个增量
                        sink.next(Result.chunk(response.content()));
                    }
                    sink.complete();
                });
            } catch (RuntimeException e) {
                sink.next(Result.failure(e));
                sink.complete();
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /** Agent 名称，用于日志与审计。 */
    public String name() {
        return name;
    }

    // ---------------- 内部实现 ----------------

    private void emitBlockingFallback(ModelClient client, ModelRequest request,
                                      FluxSink<Result> sink) {
        try {
            ModelResponse response = client.execute(request, ExecutionContext.create(traceId()));
            sink.next(Result.chunk(response.content()));
            sink.complete();
        } catch (RuntimeException e) {
            sink.next(Result.failure(e));
            sink.complete();
        }
    }

    private AgentRequest request(String input) {
        return new AgentRequest(requireInput(input), null, null, routingMetadata(),
                prompt, null, null, ExecutionContext.create(traceId()));
    }

    /**
     * 路由元数据。
     * <p>
     * 注册了工具就必须走 ReAct：默认的 DIRECT 模式是单次模型调用，
     * <b>不会处理模型返回的 toolCalls</b>，工具会被静默丢弃。
     * 这里显式指定模式，避免"我注册了工具但工具从没被调用"这种无声失败。
     */
    private java.util.Map<String, String> routingMetadata() {
        if (toolRegistry.definitions().isEmpty()) {
            return java.util.Map.of();
        }
        return java.util.Map.of("forcedMode", "REACT");
    }

    private static String traceId() {
        return UUID.randomUUID().toString();
    }

    private java.util.List<ModelMessage> messages(String input) {
        return java.util.List.of(
                ModelMessage.system(prompt),
                ModelMessage.user(requireInput(input)));
    }

    private ModelOptions options() {
        return ModelOptions.defaults();
    }

    private static String requireInput(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        return input;
    }

    private AgentRuntime resolveRuntime() {
        if (runtime != null) {
            return runtime;
        }
        DefaultAgentRuntime.Builder builder = DefaultAgentRuntime.builder()
                .model(resolveModelClient())
                .tools(toolRegistry);
        if (executor != null) {
            builder.executor(executor);
        }
        return builder.build();
    }

    private ModelClient resolveModelClient() {
        return modelClient != null ? modelClient : ModelClients.require();
    }

    /** Agent 构建器 —— 只保留业务开发者真正在意的东西。 */
    public static final class Builder {

        private final String name;
        private String prompt;
        private ModelClient modelClient;
        private ToolRegistry toolRegistry;
        private AgentRuntime runtime;
        private Executor executor;

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("agent name must not be blank");
            }
            this.name = name;
        }

        /** 业务提示词：告诉 Agent 它要做什么。 */
        public Builder prompt(String value) {
            this.prompt = value;
            return this;
        }

        /** 可选：显式指定模型客户端，不指定则使用全局注册的实现。 */
        public Builder model(ModelClient value) {
            this.modelClient = value;
            return this;
        }

        /** 可选：注册工具，让 Agent 能调用外部能力。 */
        public Builder tools(ToolRegistry value) {
            this.toolRegistry = value;
            return this;
        }

        /** 可选：使用已有运行时（例如 Spring 容器里的共享实例）。 */
        public Builder runtime(AgentRuntime value) {
            this.runtime = value;
            return this;
        }

        /** 可选：指定执行线程池。 */
        public Builder executor(Executor value) {
            this.executor = value;
            return this;
        }

        public Agent build() {
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalStateException(
                        "Agent '" + name + "' requires a prompt: call .prompt(...) before .build()");
            }
            return new Agent(this);
        }
    }
}
