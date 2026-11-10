package com.yin.ai.platform.workflow.executor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Discovers compensation handlers by application handler name. */
@Component
public class CompensationHandlerRegistry {

    private final Map<String, CompensationHandler> handlers;

    public CompensationHandlerRegistry(List<CompensationHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                CompensationHandler::handlerName, Function.identity()));
    }

    public Optional<CompensationHandler> get(String handlerName) {
        return Optional.ofNullable(handlers.get(handlerName));
    }
}
