package com.yin.ai.platform.adapter.memory.context;

import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.engine.ExecutionContext;

import java.util.HashMap;

/**
 * Anti-corruption mapper from the framework-coupled legacy execution context
 * to the framework-neutral memory context.
 */
public final class LegacyMemoryContextMapper {

    private LegacyMemoryContextMapper() {
    }

    public static MemoryContext from(ExecutionContext context) {
        if (context == null) {
            return MemoryContext.empty();
        }
        return new MemoryContext(
                context.traceId(),
                context.conversationId(),
                context.userId(),
                context.caller(),
                context.operation(),
                context.createdAt(),
                new HashMap<>(context.attributes())
        );
    }
}
