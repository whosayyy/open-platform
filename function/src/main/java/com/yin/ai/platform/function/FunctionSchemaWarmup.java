package com.yin.ai.platform.function;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.HashSet;
import java.util.Set;

/** Validates metadata and adapter availability without executing any function. */
public class FunctionSchemaWarmup {

    private final FunctionRegistry registry;

    public FunctionSchemaWarmup(FunctionRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        for (FunctionDefinition definition : registry.getAllDefinitions()) {
            if (definition.getName() == null || !definition.getName().matches("[A-Za-z0-9._:-]+")) {
                throw new IllegalStateException("Invalid function name: " + definition.getName());
            }
            Set<String> parameters = new HashSet<>();
            if (definition.getParameters() != null) {
                for (FunctionDefinition.ParamDef parameter : definition.getParameters()) {
                    if (parameter.getName() == null || parameter.getName().isBlank()
                            || !parameters.add(parameter.getName())) {
                        throw new IllegalStateException("Invalid or duplicate parameter in " + definition.getName());
                    }
                }
            }
            if (definition.getRisk() == FunctionDefinition.Risk.UNSPECIFIED) {
                throw new IllegalStateException("Function risk must be declared: " + definition.getName());
            }
        }
    }
}
