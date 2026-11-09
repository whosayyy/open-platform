package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.cost.CostLimiter;
import com.yin.ai.platform.gateway.cost.CostResult;
import com.yin.ai.platform.gateway.cost.CostUsageEvent;
import com.yin.ai.platform.gateway.cost.CostUsageRecorder;
import com.yin.ai.platform.gateway.cost.TokenCostCalculator;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.model.ModelRegistry;
import com.yin.ai.platform.gateway.stream.FinishPacket;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 成本过滤器（order=45）— 请求前检查预算，响应后计算并记录实际成本。
 * <p>
 * 在请求进入时根据预估 Token 数做预算预检（避免超限后才拦截），
 * 在响应返回后基于实际 TokenUsage 计算精确成本并写入 CostLimiter。
 */
@Slf4j
public class CostFilter implements GatewayFilter {

    private final TokenCostCalculator calculator;
    private final CostLimiter limiter;
    private final ModelRegistry modelRegistry;
    private final CostUsageRecorder usageRecorder;

    public CostFilter(TokenCostCalculator calculator, CostLimiter limiter, ModelRegistry modelRegistry) {
        this(calculator, limiter, modelRegistry, null);
    }

    public CostFilter(TokenCostCalculator calculator, CostLimiter limiter, ModelRegistry modelRegistry,
                      CostUsageRecorder usageRecorder) {
        this.calculator = calculator;
        this.limiter = limiter;
        this.modelRegistry = modelRegistry;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public String name() { return "cost"; }

    @Override
    public int order() { return 45; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        String caller = request.getCaller();
        long started = System.nanoTime();
        try {
            checkBudget(request);
            GatewayResponse response = chain.proceed(request);
            double cost = 0.0;
            ModelDefinition def = resolveModel(response.getModel());
            if (response.getTokenUsage() != null && def != null) {
                CostResult result = calculator.calculate(def, response.getTokenUsage());
                cost = result.getTotalCost();
                limiter.recordCost(caller, cost);
                log.debug("[COST] caller={} model={} cost=${} cached={}", caller, result.getModel(),
                        String.format("%.6f", cost), result.isCacheHit());
            }
            record(request, def, response.getTokenUsage(), cost, false, started);
            return response;
        } catch (RuntimeException error) {
            record(request, resolveRequestedModel(request), null, 0.0, true, started);
            throw error;
        }
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        long started = System.nanoTime();
        try {
            checkBudget(request);
        } catch (RuntimeException error) {
            record(request, resolveRequestedModel(request), null, 0.0, true, started);
            return Flux.error(error);
        }
        AtomicReference<FinishPacket> finish = new AtomicReference<>();
        Flux<StreamPacket> stream;
        try {
            stream = chain.proceed(request);
        } catch (RuntimeException error) {
            record(request, resolveRequestedModel(request), null, 0.0, true, started);
            return Flux.error(error);
        }
        return stream
                .doOnNext(packet -> {
                    if (packet != null && packet.getContent() instanceof FinishPacket value) finish.set(value);
                })
                .doOnComplete(() -> recordStreamSuccess(request, finish.get(), started))
                .doOnError(error -> record(request, resolveRequestedModel(request),
                        finish.get() != null ? finish.get().getUsage() : null, 0.0, true, started));
    }

    private void checkBudget(GatewayRequest request) {
        String modelName = request.getOptions() != null ? request.getOptions().getModel() : null;
        String caller = request.getCaller();

        ModelDefinition def = modelName != null
                ? modelRegistry.get(modelName).orElse(null)
                : modelRegistry.getDefault();
        if (def == null) return;

        Integer optMaxTokens = request.getOptions() != null ? request.getOptions().getMaxTokens() : null;
        int maxTokens = optMaxTokens != null ? optMaxTokens : def.getMaxTokens();
        double estimated = calculator.estimate(def, maxTokens / 2, maxTokens / 2);

        limiter.checkBeforeRequest(caller, estimated);
    }

    private ModelDefinition resolveModel(String modelName) {
        if (modelName == null) return modelRegistry.getDefault();
        return modelRegistry.get(modelName).orElse(modelRegistry.getDefault());
    }

    private ModelDefinition resolveRequestedModel(GatewayRequest request) {
        String modelName = request.getOptions() != null ? request.getOptions().getModel() : null;
        return resolveModel(modelName);
    }

    private void recordStreamSuccess(GatewayRequest request, FinishPacket finish, long startedNanos) {
        ModelDefinition model = finish != null && finish.getModel() != null
                ? resolveModel(finish.getModel()) : resolveRequestedModel(request);
        com.yin.ai.platform.gateway.TokenUsage usage = finish != null ? finish.getUsage() : null;
        double cost = 0.0;
        if (model != null && usage != null) {
            CostResult result = calculator.calculate(model, usage);
            cost = result.getTotalCost();
            limiter.recordCost(request.getCaller(), cost);
        }
        record(request, model, usage, cost, false, startedNanos);
    }

    private void record(GatewayRequest request, ModelDefinition model,
                        com.yin.ai.platform.gateway.TokenUsage usage,
                        double cost, boolean error, long startedNanos) {
        if (usageRecorder == null) return;
        try {
            usageRecorder.record(new CostUsageEvent(
                    safe(request.getTenantId(), "compat"), safe(request.getCaller(), "unknown"),
                    model != null ? model.getName() : "unknown", usage, cost, error,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)));
        } catch (RuntimeException recorderFailure) {
            log.warn("[COST] daily usage recording failed: {}", recorderFailure.getMessage());
        }
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
