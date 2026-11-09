package com.yin.ai.platform.workflow.executor;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用步骤处理器注册表 — 由 Spring 自动收集所有 {@link ApplicationStepHandler}。
 */
@Component
public class ApplicationStepHandlerRegistry {

    private final Map<String, ApplicationStepHandler> handlers = new HashMap<>();

    public ApplicationStepHandlerRegistry(List<ApplicationStepHandler> handlers) {
        for (ApplicationStepHandler handler : handlers) {
            this.handlers.put(handler.name(), handler);
        }
    }

    public Optional<ApplicationStepHandler> get(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    public boolean contains(String name) {
        return handlers.containsKey(name);
    }
}
