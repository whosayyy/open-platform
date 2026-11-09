package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.adapter.ModelAdapter;
import com.yin.ai.platform.gateway.model.ModelDefinition;
import com.yin.ai.platform.gateway.router.ModelRouter;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 终端过滤器（order=100）— Filter Chain 末端，调用 ModelAdapter 执行 LLM 调用。
 * <p>
 * 通过 {@link ModelRouter} 选择最佳模型，按 provider 找到对应的 {@link ModelAdapter}。
 */
@Slf4j
@Component
public class TerminalFilter implements GatewayFilter {

    private final Map<String, ModelAdapter> adapterByProvider;
    private final ModelAdapter defaultAdapter;
    private final ModelRouter modelRouter;

    public TerminalFilter(List<ModelAdapter> adapters,
                           ModelRouter modelRouter) {
        this.adapterByProvider = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        a -> a.name().toLowerCase(),
                        Function.identity(),
                        (existing, duplicate) -> {
                            log.warn("[TERMINAL] duplicate provider '{}': keeping {}, discarding {}",
                                    existing.name(), existing.getClass().getSimpleName(),
                                    duplicate.getClass().getSimpleName());
                            return existing;
                        }));
        this.defaultAdapter = adapters.isEmpty() ? null : adapters.get(0);
        this.modelRouter = modelRouter;

        log.info("[TERMINAL] registered {} adapter(s): {}",
                adapterByProvider.size(),
                adapterByProvider.keySet());
    }

    @Override
    public String name() {
        return "terminal";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        ModelAdapter adapter = resolveAdapter(request);
        return adapter.call(request);
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        ModelAdapter adapter = resolveAdapter(request);
        return adapter.stream(request);
    }

    /**
     * 通过 ModelRouter 选择模型，按 provider 解析对应的 ModelAdapter。
     */
    private ModelAdapter resolveAdapter(GatewayRequest request) {
        ModelDefinition def = modelRouter.route(request);

        // 1. 按 provider 查找 adapter
        ModelAdapter adapter = adapterByProvider.get(def.getProvider().toLowerCase());
        if (adapter != null) {
            return adapter;
        }

        // 2. 检查 adapter 的 supportedModels
        for (ModelAdapter a : adapterByProvider.values()) {
            if (a.supportedModels().contains(def.getName())) {
                return a;
            }
        }

        // 3. 回退到默认 adapter
        if (defaultAdapter != null) {
            log.warn("[TERMINAL] no adapter for provider '{}' / model '{}', falling back to '{}'",
                    def.getProvider(), def.getName(), defaultAdapter.name());
            return defaultAdapter;
        }

        throw new IllegalStateException(
                "No ModelAdapter found for model='" + def.getName()
                + "' (provider='" + def.getProvider() + "'). "
                + "Available providers: " + adapterByProvider.keySet());
    }
}
