package com.yin.ai.platform.quickstart;

import com.yin.ai.platform.core.model.ModelClient;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 门面的模型发现机制。
 * <p>
 * 业务开发者不应该为了跑一个 Agent 就去实现 {@link ModelClient}。这里提供一个
 * 与应用框架解耦的注册点：Spring Boot 自动配置在启动时注入容器里的实现，
 * 纯 Java 场景则可以自行 {@link #register(ModelClient)}。
 * <p>
 * 使用全局可变单例是刻意的取舍：它是"零配置入口"的代价，且只在应用启动阶段写入一次。
 */
public final class ModelClients {

    private static final AtomicReference<Supplier<ModelClient>> SUPPLIER = new AtomicReference<>();

    private ModelClients() {
    }

    /** 注册一个模型客户端（后注册的覆盖先注册的）。 */
    public static void register(ModelClient client) {
        if (client == null) {
            SUPPLIER.set(null);
            return;
        }
        SUPPLIER.set(() -> client);
    }

    /** 注册一个延迟提供者，便于与容器生命周期配合。 */
    public static void register(Supplier<ModelClient> supplier) {
        SUPPLIER.set(supplier);
    }

    /** 读取当前注册的模型客户端。 */
    public static Optional<ModelClient> current() {
        Supplier<ModelClient> supplier = SUPPLIER.get();
        if (supplier == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(supplier.get());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 读取模型客户端，缺失时给出可执行的修复指引而不是 NPE。 */
    public static ModelClient require() {
        return current().orElseThrow(() -> new IllegalStateException(
                "No ModelClient is available. Either call ModelClients.register(...) "
                        + "or configure a model provider (Spring Boot apps get one "
                        + "auto-registered by com.yin.ai.platform.starter)."));
    }

    /** 仅供测试使用：清除注册。 */
    static void reset() {
        SUPPLIER.set(null);
    }
}
